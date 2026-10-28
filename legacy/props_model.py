"""Credibility-weighted props model, per-game similarity, and Quarter-Kelly."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Optional

import numpy as np
import pandas as pd

from config import (
    BASE_WEIGHTS,
    BLEND_A,
    BLEND_B,
    BUHLMANN_K,
    DEFAULT_BANKROLL,
    H2H_K,
    KELLY_FRACTION,
    MAX_STAKE_PCT,
    SIMILARITY_FEATURES,
    STAT_ALIASES,
    STAT_BASE_WEIGHTS,
)

H2H_PATTERN = re.compile(
    r"""
    ^\s*
    (?P<team>.*?)
    (?:\s*(?P<pct>\d+(?:[.,]\d+)?)\s*%)?
    (?:\s*\(\s*(?P<n>\d+)\s*\))?
    \s*$
    """,
    re.VERBOSE | re.UNICODE,
)

TEAM_STOPWORDS = {
    "bc", "b", "c", "basket", "basketball", "club", "kk", "sk", "as", "gs",
    "athens", "piraeus", "istanbul", "moscow", "munich", "milano", "milan",
    "the", "vs", "at",
}


@dataclass
class H2HSpec:
    team: Optional[str] = None
    n: Optional[int] = None
    hit_rate: Optional[float] = None


def parse_h2h(raw: object) -> H2HSpec:
    """Parse H2H input.

    Examples: 'Real Madrid', 'Real Madrid 100% (2)', '100% (2)'.
    A team name marks those games as H2H-only so they are left out of the
    other filters and the similarity layer.
    """
    text = str(raw or "").strip()
    if not text:
        return H2HSpec()
    match = H2H_PATTERN.match(text)
    if not match:
        return H2HSpec(team=_clean_team(text))

    team = _clean_team(match.group("team") or "")
    n_txt = match.group("n")
    pct_txt = match.group("pct")
    n = int(n_txt) if n_txt else None
    hit = None
    if pct_txt:
        hit = float(pct_txt.replace(",", ".")) / 100.0
        hit = min(max(hit, 0.0), 1.0)
    return H2HSpec(team=team, n=n, hit_rate=hit)


def _clean_team(raw: str) -> Optional[str]:
    team = str(raw or "").strip(" -–|,")
    team = re.sub(r"^(?:vs\.?|@)\s+", "", team, flags=re.IGNORECASE).strip()
    if team.lower() in {"", "h2h", "vs"}:
        return None
    return team or None


@dataclass(frozen=True)
class Estimate:
    """A sheet input: one floor, an inclusive range, or one exact value."""

    low: float
    high: Optional[float] = None
    exact: bool = False

    def label(self) -> str:
        if self.exact:
            return f"={_num(self.low)}"
        if self.high is None:
            return f">={_num(self.low)}"
        return f"{_num(self.low)}-{_num(self.high)}"


def _num(value: float) -> str:
    return f"{value:.4f}".rstrip("0").rstrip(".")


_RANGE_RE = re.compile(
    r"""
    ^\s*
    (?P<low>-?\d+(?:[.,]\d+)?)
    \s*(?:-|–|—|/|to|έως|εως|με)\s*
    (?P<high>-?\d+(?:[.,]\d+)?)
    \s*$
    """,
    re.VERBOSE | re.IGNORECASE,
)


_EXACT_RE = re.compile(
    r"""
    ^\s*
    (?:
        !\s*(?P<lead>-?\d+(?:[.,]\d+)?)
        |
        (?P<trail>-?\d+(?:[.,]\d+)?)\s*!
    )
    \s*$
    """,
    re.VERBOSE,
)


def _exact_estimate(number: str) -> Estimate:
    value = float(number.replace(",", "."))
    return Estimate(low=value, high=value, exact=True)


def parse_estimate(value: object) -> Optional[Estimate]:
    """Parse '21' as a floor, '20-25' as a range, or '21!' as that value only."""
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return None
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return Estimate(low=float(value))
    text = str(value).strip().replace("%", "")
    if not text or text.lower() in {"nan", "none"}:
        return None
    exact = _EXACT_RE.match(text)
    if exact:
        return _exact_estimate(exact.group("lead") or exact.group("trail"))
    match = _RANGE_RE.match(text)
    if match:
        low = float(match.group("low").replace(",", "."))
        high = float(match.group("high").replace(",", "."))
        if low > high:
            low, high = high, low
        return Estimate(low=low, high=high)
    if re.fullmatch(r"-?\d+(?:[.,]\d+)?", text):
        return Estimate(low=float(text.replace(",", ".")))
    raise ValueError(
        f"Expected a number, 21! for that value only, or a range like 20-25, got '{value}'"
    )


_PACE_URL_RE = re.compile(r"\((https?://[^)]+)\)", re.IGNORECASE)


def parse_pace_cell(value: object) -> tuple[Optional[Estimate], str]:
    """Parse Exp Pace: '76-84 (https://...)' or a plain number/range."""
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return None, ""
    text = str(value).strip()
    if not text or text.lower() in {"nan", "none"}:
        return None, ""
    url = ""
    match = _PACE_URL_RE.search(text)
    if match:
        url = match.group(1).strip()
        text = (text[: match.start()] + text[match.end() :]).strip(" -")
    if not text:
        return None, url
    return parse_estimate(text), url


@dataclass
class BetInput:
    date: str
    player: str
    url: str
    stat_type: str
    line: Optional[float]
    odds: Optional[float]
    exp_mins: Optional[Estimate]
    exp_fga: Optional[Estimate]
    exp_fta: Optional[Estimate]
    exp_usg: Optional[Estimate]
    exp_pace: Optional[Estimate]
    exp_3pa: Optional[Estimate]
    is_home: Optional[bool]
    h2h_raw: str
    sheet_row: int
    pace_url: str = ""
    url_from_memory: bool = False
    pace_from_memory: bool = False


@dataclass
class FactorResult:
    name: str
    n: int
    z: float
    base_weight: float
    effective_weight: float
    hit_rate: Optional[float]
    notes: str = ""


@dataclass
class StakePlan:
    ev: float
    implied: float
    kelly_full: float
    kelly_quarter: float
    stake_euros: float
    capped: bool
    status: str


@dataclass
class ModelResult:
    model_prob: float
    p_filters: Optional[float]
    p_similarity: Optional[float]
    factors: list[FactorResult]
    stake: StakePlan
    notes: list[str] = field(default_factory=list)


def parse_bool(value: object) -> Optional[bool]:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return None
    text = str(value).strip().lower()
    if text in {"", "nan", "none"}:
        return None
    if text in {"true", "1", "yes", "y", "home", "h"}:
        return True
    if text in {"false", "0", "no", "n", "away", "a"}:
        return False
    return None


def canonical_stat(stat_type: str) -> Optional[str]:
    raw = str(stat_type).strip().lower()
    folded = (
        raw.replace("ά", "α").replace("έ", "ε").replace("ή", "η")
        .replace("ί", "ι").replace("ό", "ο").replace("ύ", "υ").replace("ώ", "ω")
    )
    if "τριπ" in folded or "3ποντ" in folded:
        if "προσπ" in folded or "attempt" in raw:
            return "3pa"
        return "3pm"
    if "ασιστ" in folded:
        return "ast"
    key = re.sub(r"[^a-z0-9+]", "", raw)
    for name, aliases in STAT_ALIASES.items():
        if key == name or key in aliases:
            return name
    return None


def stat_family(stat_type: str) -> str:
    canon = canonical_stat(stat_type)
    if canon in {"trb", "orb", "drb"}:
        return "reb"
    if canon in {"3pm", "3pa"}:
        return "three"
    if canon == "ast":
        return "ast"
    return "default"


def base_weights_for(stat_type: str) -> dict[str, float]:
    canon = canonical_stat(stat_type)
    if canon and canon in STAT_BASE_WEIGHTS:
        return dict(STAT_BASE_WEIGHTS[canon])
    return dict(BASE_WEIGHTS)


def resolve_stat_column(stat_type: str, games: pd.DataFrame) -> str:
    key = re.sub(r"[^a-z0-9+]", "", str(stat_type).strip().lower())
    for canonical, aliases in STAT_ALIASES.items():
        if key == canonical or key in aliases:
            if canonical in games.columns:
                return canonical
    for col in games.columns:
        if _norm(col) == key:
            return col
    raise KeyError(
        f"Stat type '{stat_type}' not found in game log columns: {list(games.columns)}"
    )


def _norm(text: object) -> str:
    return re.sub(r"[^a-z0-9+]", "", str(text).strip().lower())


def credibility(n: int, k: float = BUHLMANN_K) -> float:
    if n < 0:
        raise ValueError("N must be >= 0")
    denom = n + k
    return 0.0 if denom == 0 else n / denom


def _hit_series(games: pd.DataFrame, stat_col: str, line: float) -> pd.Series:
    return games[stat_col].astype(float) > float(line)


def _estimate_mask(games: pd.DataFrame, column: str, estimate: Estimate) -> pd.Series:
    """Floor keeps games at or above low. A range keeps games inside [low, high].

    An exact input (21!) keeps only games equal to that number.
    """
    if column not in games.columns:
        return pd.Series(False, index=games.index)
    values = games[column].astype(float)
    if estimate.exact:
        close = np.isclose(
            values.to_numpy(dtype=float),
            float(estimate.low),
            rtol=0.0,
            atol=1e-4,
            equal_nan=False,
        )
        return pd.Series(close, index=games.index)
    mask = values >= float(estimate.low)
    if estimate.high is not None:
        mask &= values <= float(estimate.high)
    return mask


def _team_tokens(name: object) -> set[str]:
    raw = _norm(name)
    tokens = set(re.findall(r"[a-z0-9]+", str(name).lower()))
    tokens = {t for t in tokens if t not in TEAM_STOPWORDS and len(t) >= 3}
    if raw:
        tokens.add(raw)
    return tokens


def _opponent_mask(games: pd.DataFrame, team: str) -> pd.Series:
    if not team or "opp" not in games.columns:
        return pd.Series(False, index=games.index)
    needle_tokens = _team_tokens(team)
    needle_norm = _norm(team)

    def matches(opp: object) -> bool:
        opp_norm = _norm(opp)
        if not opp_norm:
            return False
        if needle_norm and (needle_norm in opp_norm or opp_norm in needle_norm):
            return True
        opp_tokens = _team_tokens(opp)
        return bool(needle_tokens and (needle_tokens & opp_tokens))

    return games["opp"].fillna("").map(matches)


def active_base_weights(bet: BetInput) -> dict[str, float]:
    """Drop missing factors and re-normalize remaining base weights to 1.0."""
    spec = parse_h2h(bet.h2h_raw)
    raw = base_weights_for(bet.stat_type)

    if "mins" in raw and bet.exp_mins is None:
        raw["mins"] = 0.0
    if "fga" in raw and bet.exp_fga is None:
        raw["fga"] = 0.0
    if "3pa" in raw and bet.exp_3pa is None:
        raw["3pa"] = 0.0
    # On the points scheme, 3PA is only for points. Threes keep their own 3PA weight.
    elif (
        "3pa" in raw
        and canonical_stat(bet.stat_type) != "pts"
        and stat_family(bet.stat_type) != "three"
    ):
        raw["3pa"] = 0.0
    if "pace" in raw and bet.exp_pace is None:
        raw["pace"] = 0.0
    # FTA is a points-only attempt filter. Other stats ignore the column.
    if "fta" in raw and (bet.exp_fta is None or canonical_stat(bet.stat_type) != "pts"):
        raw["fta"] = 0.0
    if "usg" in raw and bet.exp_usg is None:
        raw["usg"] = 0.0
    if "home" in raw and bet.is_home is None:
        raw["home"] = 0.0
    # A team name is enough: H2H is then taken from the log vs that team.
    if "h2h" in raw and spec.hit_rate is None and not spec.team:
        raw["h2h"] = 0.0

    if "volume" in raw:
        volume_present = []
        if bet.exp_usg is not None:
            volume_present.append("usg")
        if bet.exp_pace is not None:
            volume_present.append("pace")
        if not volume_present:
            raw["volume"] = 0.0

    total = sum(raw.values())
    if total <= 0:
        raise ValueError("No active indicators: all expected inputs are blank.")
    return {k: v / total for k, v in raw.items() if v > 0}


def _volume_mask(games: pd.DataFrame, bet: BetInput) -> pd.Series:
    mask = pd.Series(True, index=games.index)
    applied = False
    if bet.exp_usg is not None and "usg" in games.columns:
        mask &= _estimate_mask(games, "usg", bet.exp_usg)
        applied = True
    if bet.exp_pace is not None and "pace" in games.columns:
        mask &= _estimate_mask(games, "pace", bet.exp_pace)
        applied = True
    if not applied:
        return pd.Series(False, index=games.index)
    return mask


def _factor_band(bet: BetInput, factor: str) -> str:
    if factor == "mins" and bet.exp_mins is not None:
        return bet.exp_mins.label()
    if factor == "fga" and bet.exp_fga is not None:
        return bet.exp_fga.label()
    if factor == "3pa" and bet.exp_3pa is not None:
        return bet.exp_3pa.label()
    if factor == "pace" and bet.exp_pace is not None:
        return bet.exp_pace.label()
    if factor == "fta" and bet.exp_fta is not None:
        return bet.exp_fta.label()
    if factor == "usg" and bet.exp_usg is not None:
        return bet.exp_usg.label()
    if factor == "volume":
        parts = []
        if bet.exp_usg is not None:
            parts.append(f"usg {bet.exp_usg.label()}")
        if bet.exp_pace is not None:
            parts.append(f"pace {bet.exp_pace.label()}")
        return ", ".join(parts)
    return ""


def _factor_mask(games: pd.DataFrame, bet: BetInput, factor: str) -> pd.Series:
    if factor == "mins":
        return _estimate_mask(games, "min", bet.exp_mins)
    if factor == "fga":
        return _estimate_mask(games, "fga", bet.exp_fga)
    if factor == "3pa":
        return _estimate_mask(games, "3pa", bet.exp_3pa)
    if factor == "volume":
        return _volume_mask(games, bet)
    if factor == "pace":
        return _estimate_mask(games, "pace", bet.exp_pace)
    if factor == "fta":
        return _estimate_mask(games, "fta", bet.exp_fta)
    if factor == "usg":
        return _estimate_mask(games, "usg", bet.exp_usg)
    if factor == "home":
        if "is_home" not in games.columns or bet.is_home is None:
            return pd.Series(False, index=games.index)
        return games["is_home"] == bet.is_home
    raise KeyError(factor)


def _split_h2h_games(
    games: pd.DataFrame, team: Optional[str]
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Return (other games, games vs team). Other games exclude the H2H sample."""
    if not team:
        return games, games.iloc[0:0]
    mask = _opponent_mask(games, team)
    return games.loc[~mask], games.loc[mask]


def model_a_filters(
    games: pd.DataFrame,
    bet: BetInput,
    stat_col: str,
) -> tuple[Optional[float], list[FactorResult]]:
    """Hit rates on at-least subsets, then shrink toward H2H with its own K.

    Games against the named H2H team are removed from every other filter so
    the same games are not counted twice.
    """
    bases = active_base_weights(bet)
    spec = parse_h2h(bet.h2h_raw)
    rest_games, h2h_games = _split_h2h_games(games, spec.team)
    hits = _hit_series(rest_games, stat_col, bet.line)
    factors: list[FactorResult] = []
    rest_note = "at-least subset"
    if spec.team and len(h2h_games):
        rest_note = f"at-least subset, excluding {len(h2h_games)} vs {spec.team}"

    rest_raw = 0.0
    rest_p = 0.0
    for name, base in bases.items():
        if name == "h2h":
            continue
        mask = _factor_mask(rest_games, bet, name)
        n = int(mask.sum())
        z = credibility(n)
        hit_rate = float(hits[mask].mean()) if n else None
        eff = base * z
        label = name
        if name == "home":
            label = "home" if bet.is_home else "away"
        band = _factor_band(bet, name)
        kind = "inside range" if "-" in band else "at-least subset"
        note_parts = [kind]
        if "excluding" in rest_note:
            note_parts.append(rest_note.split(", ", 1)[1])
        if band:
            note_parts.append(band)
        note = "; ".join(note_parts)
        factors.append(
            FactorResult(
                name=label,
                n=n,
                z=z,
                base_weight=base,
                effective_weight=eff,
                hit_rate=hit_rate,
                notes=note,
            )
        )
        rest_raw += eff
        if hit_rate is not None:
            rest_p += eff * hit_rate

    p_rest = (rest_p / rest_raw) if rest_raw > 0 else None

    h2h_rate: Optional[float] = None
    h2h_n = 0
    h2h_note = ""
    if spec.hit_rate is not None:
        h2h_rate = spec.hit_rate
        h2h_n = spec.n if spec.n is not None else max(len(h2h_games), 1)
        h2h_note = f"manual sheet H2H {h2h_rate:.0%} (N={h2h_n})"
        if spec.team:
            h2h_note += f"; {len(h2h_games)} games vs {spec.team} left out of other filters"
    elif spec.team and len(h2h_games):
        h2h_hits = _hit_series(h2h_games, stat_col, bet.line)
        h2h_n = int(len(h2h_games))
        h2h_rate = float(h2h_hits.mean()) if h2h_n else None
        if h2h_rate is not None:
            h2h_note = (
                f"log H2H vs {spec.team} {h2h_rate:.0%} (N={h2h_n}), "
                "left out of other filters"
            )

    if "h2h" in bases and h2h_rate is not None:
        z_h2h = credibility(h2h_n, k=H2H_K)
        factors.append(
            FactorResult(
                name="h2h",
                n=h2h_n,
                z=z_h2h,
                base_weight=bases["h2h"],
                effective_weight=z_h2h,
                hit_rate=h2h_rate,
                notes=h2h_note,
            )
        )
        if p_rest is not None and z_h2h > 0:
            # A short H2H sample must not outrank its listed weight.
            h2h_share = min(z_h2h, bases["h2h"])
            p = (1.0 - h2h_share) * p_rest + h2h_share * h2h_rate
            rest_share = 1.0 - h2h_share
            for f in factors:
                if f.name == "h2h":
                    f.effective_weight = h2h_share
                elif rest_raw > 0:
                    f.effective_weight = rest_share * (f.effective_weight / rest_raw)
            return p, factors
        if z_h2h > 0:
            return h2h_rate, factors

    if rest_raw <= 0:
        return None, factors

    for f in factors:
        if f.name != "h2h" and rest_raw > 0:
            f.effective_weight = f.effective_weight / rest_raw
    return p_rest, factors


def model_b_similarity(
    games: pd.DataFrame,
    bet: BetInput,
    stat_col: str,
) -> Optional[float]:
    """One weight per unique game (avoids double-counting overlapping filters).

    Games vs the named H2H team are omitted here; they are scored only as H2H.
    """
    spec = parse_h2h(bet.h2h_raw)
    rest_games, _h2h_games = _split_h2h_games(games, spec.team)
    work = rest_games.dropna(subset=[stat_col]).copy()
    if work.empty:
        return None

    feature_plan: list[tuple[str, Estimate]] = []
    family = stat_family(bet.stat_type)
    allowed = SIMILARITY_FEATURES.get(family, SIMILARITY_FEATURES["default"])
    fta_estimate = bet.exp_fta if canonical_stat(bet.stat_type) == "pts" else None
    pa_estimate = (
        bet.exp_3pa
        if canonical_stat(bet.stat_type) == "pts" or family == "three"
        else None
    )
    candidates = (
        ("min", bet.exp_mins),
        ("fga", bet.exp_fga),
        ("fta", fta_estimate),
        ("3pa", pa_estimate),
        ("usg", bet.exp_usg),
        ("pace", bet.exp_pace),
    )
    for col, estimate in candidates:
        if col not in allowed:
            continue
        if estimate is None or col not in work.columns:
            continue
        feature_plan.append((col, estimate))

    weights = np.ones(len(work), dtype=float)
    hits = (work[stat_col].astype(float) > float(bet.line)).to_numpy(dtype=float)

    for col, estimate in feature_plan:
        weights *= _similarity_kernel(work[col], estimate)

    if bet.is_home is not None and "is_home" in work.columns:
        match = work["is_home"] == bet.is_home
        weights *= np.where(match.fillna(False).to_numpy(), 1.15, 0.35)

    total = float(weights.sum())
    if total <= 0:
        return None
    return float(np.dot(weights, hits) / total)


def _similarity_kernel(series: pd.Series, estimate: Estimate) -> np.ndarray:
    """Weight games by how close they are to a floor, a range, or one exact value."""
    values = series.astype(float)
    if estimate.exact:
        return np.where(
            np.isclose(
                values.to_numpy(dtype=float),
                float(estimate.low),
                rtol=0.0,
                atol=1e-4,
                equal_nan=False,
            ),
            1.0,
            0.0,
        ).astype(float)
    filled = values.fillna(values.median())
    std = float(values.std(skipna=True) or 0.0)
    if estimate.high is None:
        target = float(estimate.low)
        scale = std if std > 1e-9 else max(abs(target), 1.0) * 0.25
        z = (filled - target) / scale
        below = values < target
        kernel = np.exp(-0.5 * np.clip(z.to_numpy(), -8, 8) ** 2)
        return np.where(below.fillna(True).to_numpy(), kernel * 0.15, np.maximum(kernel, 0.55))

    low = float(estimate.low)
    high = float(estimate.high)
    scale = std if std > 1e-9 else max(high - low, 1.0) * 0.5
    arr = filled.to_numpy(dtype=float)
    dist = np.where(arr < low, low - arr, np.where(arr > high, arr - high, 0.0))
    kernel = np.exp(-0.5 * np.clip(dist / scale, 0, 8) ** 2)
    outside = values.isna() | (values < low) | (values > high)
    return np.where(outside.to_numpy(), kernel * 0.15, np.maximum(kernel, 1.0))


def combine_probabilities(p_a: Optional[float], p_b: Optional[float]) -> float:
    if p_a is None and p_b is None:
        raise ValueError("Both model layers failed.")
    if p_a is None:
        return float(p_b)
    if p_b is None:
        return float(p_a)
    return float(BLEND_A * p_a + BLEND_B * p_b)


def compute_stake(
    p: float,
    odds: float,
    bankroll: float = DEFAULT_BANKROLL,
) -> StakePlan:
    if odds <= 1.0:
        raise ValueError("Odds must be > 1.0")
    implied = 1.0 / odds
    ev = p * odds - 1.0
    cap = round(bankroll * MAX_STAKE_PCT, 2)
    if ev <= 0:
        return StakePlan(
            ev=ev,
            implied=implied,
            kelly_full=0.0,
            kelly_quarter=0.0,
            stake_euros=0.0,
            capped=False,
            status="NO BET",
        )
    kelly_full = max(0.0, min(1.0, ev / (odds - 1.0)))
    kelly_q = KELLY_FRACTION * kelly_full
    stake = bankroll * kelly_q
    capped = stake > cap
    if capped:
        stake = cap
    return StakePlan(
        ev=ev,
        implied=implied,
        kelly_full=kelly_full,
        kelly_quarter=kelly_q,
        stake_euros=round(stake, 2),
        capped=capped,
        status="BET",
    )


def _gr_num(value: float) -> str:
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return text.replace(".", ",")


def _gr_pct(value: float) -> str:
    return f"{round(float(value) * 100):.0f}%"


def _stat_phrase(stat_type: str) -> str:
    canon = canonical_stat(stat_type)
    names = {
        "pts": "πόντοι",
        "3pm": "τρίποντα",
        "3pa": "προσπάθειες τρίποντου",
        "trb": "ριμπάουντ",
        "orb": "επιθετικά ριμπάουντ",
        "drb": "αμυντικά ριμπάουντ",
        "ast": "ασίστ",
    }
    return names.get(canon or "", str(stat_type).strip() or "στατ")


def _factor_sentence(bet: BetInput, factor: FactorResult) -> str:
    hit = _gr_pct(factor.hit_rate or 0.0)
    n = factor.n
    games = "παιχνίδι" if n == 1 else "παιχνίδια"
    spec = parse_h2h(bet.h2h_raw)
    if factor.name == "mins" and bet.exp_mins is not None:
        return f"Όταν έπαιξε {bet.exp_mins.label()} λεπτά, πέρασε τη γραμμή στο {hit}, σε {n} {games}."
    if factor.name == "fga" and bet.exp_fga is not None:
        return f"Με {bet.exp_fga.label()} σουτ, σε {n} {games}, η γραμμή περνάει στο {hit}."
    if factor.name == "fta" and bet.exp_fta is not None:
        return f"Με {bet.exp_fta.label()} βολές, σε {n} {games}, η γραμμή περνάει στο {hit}."
    if factor.name == "3pa" and bet.exp_3pa is not None:
        return f"Με {bet.exp_3pa.label()} προσπάθειες τρίποντου, σε {n} {games}, η γραμμή περνάει στο {hit}."
    if factor.name == "usg" and bet.exp_usg is not None:
        return f"Με usage {bet.exp_usg.label()}, σε {n} {games}, η γραμμή περνάει στο {hit}."
    if factor.name == "pace" and bet.exp_pace is not None:
        return f"Με pace {bet.exp_pace.label()}, σε {n} {games}, η γραμμή περνάει στο {hit}."
    if factor.name == "home":
        return f"Εντός έδρας πέρασε τη γραμμή στο {hit}, σε {n} {games}."
    if factor.name == "away":
        return f"Εκτός έδρας πέρασε τη γραμμή στο {hit}, σε {n} {games}."
    if factor.name == "h2h":
        team = spec.team or "τον αντίπαλο"
        return f"Απέναντι σε {team}, σε {n} {games}, πέρασε τη γραμμή στο {hit}."
    if factor.name == "volume":
        return f"Στον όγκο της βραδιάς, σε {n} {games}, η γραμμή περνάει στο {hit}."
    return f"{factor.name}: {hit} σε {n} {games}."


def public_post(bet: BetInput, result: ModelResult) -> str:
    """Plain-language writeup for posting. No model weights."""
    line = _gr_num(bet.line) if bet.line is not None else "?"
    odds = _gr_num(bet.odds) if bet.odds is not None else "?"
    headline = f"{bet.player}, over {line} {_stat_phrase(bet.stat_type)} @ {odds}"

    place = ""
    if bet.is_home is True:
        place = "Εντός έδρας"
    elif bet.is_home is False:
        place = "Εκτός έδρας"
    team = parse_h2h(bet.h2h_raw).team
    if team:
        place = f"{place}, απέναντι σε {team}" if place else f"Απέναντι σε {team}"

    implied = result.stake.implied
    model = result.model_prob
    decision = (
        f"Η απόδοση κρύβει περίπου {_gr_pct(implied)}. Το μοντέλο δίνει {_gr_pct(model)}."
    )
    if result.stake.status == "BET":
        stake = _gr_num(result.stake.stake_euros)
        if result.stake.capped:
            decision += f" Παίζεται στο ανώτατο ποντάρισμα των {stake}€."
        else:
            decision += f" Παίζεται με {stake}€."
    else:
        decision += " Δεν παίζεται."

    usable = [f for f in result.factors if f.n > 0 and f.hit_rate is not None]
    ranked = sorted(usable, key=lambda f: f.effective_weight, reverse=True)
    body: list[str] = []
    if ranked:
        lead = ranked[0]
        body.append(_factor_sentence(bet, lead))
        supporters = [
            f for f in ranked[1:]
            if f.effective_weight >= 0.08 and f.hit_rate is not None and f.hit_rate >= implied + 0.08
        ]
        supporters.sort(key=lambda f: f.hit_rate or 0.0, reverse=True)
        if supporters:
            body.append(_factor_sentence(bet, supporters[0]))
        weak = min(usable, key=lambda f: f.hit_rate or 1.0)
        if weak is not lead and weak.hit_rate is not None and weak.hit_rate < implied:
            body.append(_factor_sentence(bet, weak))

    parts = [headline, ""]
    context = ". ".join(bit for bit in (place, decision) if bit)
    if context:
        parts.append(context)
        parts.append("")
    parts.extend(body)
    return "\n".join(parts).strip() + "\n"


def evaluate_bet(games: pd.DataFrame, bet: BetInput) -> ModelResult:
    notes: list[str] = []
    stat_col = resolve_stat_column(bet.stat_type, games)
    usable = games.dropna(subset=[stat_col])
    if usable.empty:
        raise ValueError(f"No games with a value for {bet.stat_type}.")

    spec = parse_h2h(bet.h2h_raw)
    _rest, h2h_games = _split_h2h_games(usable, spec.team)
    if spec.team and len(h2h_games):
        notes.append(
            f"H2H vs {spec.team}: {len(h2h_games)} games counted only as H2H, "
            "removed from the other filters and from similarity."
        )
    elif spec.team:
        notes.append(
            f"H2H team '{spec.team}' matched 0 games; other stats use the full log."
        )
    elif spec.hit_rate is not None:
        notes.append(
            "H2H has a hit rate but no team name, so those games stay inside the other stats."
        )

    p_a, factors = model_a_filters(usable, bet, stat_col)
    p_b = model_b_similarity(usable, bet, stat_col)
    if p_a is None:
        notes.append("Filter layer unused (zero credibility).")
    if p_b is None:
        notes.append("Similarity layer unused.")

    p = combine_probabilities(p_a, p_b)
    p = min(max(p, 0.0), 1.0)
    if canonical_stat(bet.stat_type) == "pts":
        if bet.exp_fta is not None and bet.exp_3pa is not None:
            notes.append(
                "PTS weights with FTA and 3PA: Mins 25%, FGA 21%, FTA 8%, 3PA 8%, "
                "Usage 13%, Pace 8%, Home/Away 8%, H2H 8%. Each adds 10 points, then the set is scaled to 100."
            )
        elif bet.exp_fta is not None:
            notes.append(
                "PTS weights with FTA: Mins 27%, FGA 23%, FTA 9%, Usage 14%, "
                "Pace 9%, Home/Away 9%, H2H 9%. FTA adds 10 points and the set is scaled to 100."
            )
        elif bet.exp_3pa is not None:
            notes.append(
                "PTS weights with 3PA: Mins 27%, FGA 23%, 3PA 9%, Usage 14%, "
                "Pace 9%, Home/Away 9%, H2H 9%. 3PA adds 10 points and the set is scaled to 100."
            )
        else:
            notes.append(
                "PTS weights: Mins 30%, FGA 25%, Usage 15%, Pace 10%, "
                "Home/Away 10%, H2H 10%."
            )
    if stat_family(bet.stat_type) == "reb":
        notes.append(
            "REB weights: Mins 40%, Pace 25%, Home/Away 20%, H2H 15% "
            "(FGA/USG ignored). Name the opponent in H2H Opponent "
            "(e.g. Real Madrid or Real Madrid 100% (2))."
        )
    if stat_family(bet.stat_type) == "ast":
        notes.append(
            "AST weights: Mins 30%, Pace 20%, FGA 15%, Usage 15%, "
            "Home/Away 10%, H2H 10%."
        )
    if stat_family(bet.stat_type) == "three":
        notes.append(
            "3PT weights: 3PA 25%, Mins 20%, FGA 15%, Volume 15% (usage and pace), "
            "Home/Away 15%, H2H 10%."
        )
    bands = [
        f"{label} {est.label()}"
        for label, est in (
            ("mins", bet.exp_mins),
            ("fga", bet.exp_fga),
            (
                "3pa",
                bet.exp_3pa
                if canonical_stat(bet.stat_type) == "pts" or stat_family(bet.stat_type) == "three"
                else None,
            ),
            ("usg", bet.exp_usg),
            ("pace", bet.exp_pace),
            ("fta", bet.exp_fta if canonical_stat(bet.stat_type) == "pts" else None),
        )
        if est is not None
    ]
    if bands:
        notes.append("Inputs: " + ", ".join(bands))
    if bet.exp_fta is not None and canonical_stat(bet.stat_type) != "pts":
        notes.append("FTA column filled but ignored; free throws are used only on points.")
    if (
        bet.exp_3pa is not None
        and canonical_stat(bet.stat_type) != "pts"
        and stat_family(bet.stat_type) != "three"
    ):
        notes.append("x3PA filled but ignored; three-point attempts are used on points and threes.")
    if (
        bet.exp_3pa is not None
        and canonical_stat(bet.stat_type) == "pts"
        and "3pa" not in usable.columns
    ):
        notes.append("x3PA is set, but the game log has no 3PA column, so it was not used.")
    if (
        bet.exp_fta is not None
        and canonical_stat(bet.stat_type) == "pts"
        and "fta" not in usable.columns
    ):
        notes.append("FTA is set, but the game log has no FTA column, so it was not used.")
    if bet.exp_pace is not None and "pace" not in usable.columns:
        notes.append("Exp Pace is set, but no pace column was attached, so pace was not used.")
    stake = compute_stake(p, bet.odds)
    return ModelResult(
        model_prob=p,
        p_filters=p_a,
        p_similarity=p_b,
        factors=factors,
        stake=stake,
        notes=notes,
    )
