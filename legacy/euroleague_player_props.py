#!/usr/bin/env python3
"""
Euroleague Player Props — δυναμικός υπολογισμός πιθανότητας επιβεβαίωσης.

Πηγή δεδομένων (Google Sheet, gid=0):
https://docs.google.com/spreadsheets/d/1bwA3l_aKJAR5D-1V_idnBIfc1BruZMceqDK92F4_yHo/edit?gid=0#gid=0

Στήλες φύλλου:
  Player | Last 5 | On Xminutes | On XFGs | H2H | Home/Away on Xmins | Line | Stat | Odds | Result
  | Model Probability | Edge

Γράφει ΜΟΝΟ Model Probability και Edge στο ίδιο Sheet URL.
Δεν πειράζει Result ούτε τα πεδία εισόδου (Player, Last 5, Odds, κ.λπ.).

Προσαρμόζει ομαλά τις βαρύτητες με το Bühlmann Credibility Model:
    Z_i = N_i / (N_i + K)
χωρίς hard thresholds στο μέγεθος δείγματος.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import math
import os
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Iterable, Optional


# --------------------------------------------------------------------6-------
# Google Sheet
# ---------------------------------------------------------------------------

SHEET_ID = "1bwA3l_aKJAR5D-1V_idnBIfc1BruZMceqDK92F4_yHo"
SHEET_GID = "0"
SHEET_URL = (
    f"https://docs.google.com/spreadsheets/d/{SHEET_ID}/edit?gid={SHEET_GID}#gid={SHEET_GID}"
)
# Δημόσιο CSV export — δουλεύει όταν το φύλλο είναι "Anyone with the link can view".
SHEET_CSV_URL = (
    f"https://docs.google.com/spreadsheets/d/{SHEET_ID}/export?format=csv&gid={SHEET_GID}"
)
SHEET_GVIZ_CSV_URL = (
    f"https://docs.google.com/spreadsheets/d/{SHEET_ID}/gviz/tq?tqx=out:csv&gid={SHEET_GID}"
)

# Apps Script Web App (γράφει Model Probability / Edge στο ίδιο Sheet).
SHEET_WEBAPP_URL = os.environ.get(
    "SHEET_WEBAPP_URL",
    "https://script.google.com/macros/s/AKfycbwU78kOge7oMKKtiQrh9xC0_fIAO46EDeR-pm7PX_SZdphtmQqitUBTQqMXWssfnOhpyg/exec",
).strip()
SERVICE_ACCOUNT_FILE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "service_account.json"
)

# Γράφονται μόνο αυτές. Όλα τα άλλα πεδία μένουν όπως τα συμπληρώνεις εσύ.
COMPUTED_SHEET_COLUMNS = [
    "Model Probability",
    "Edge",
]
PROTECTED_COLUMNS = {
    "Player",
    "Last 5",
    "On Xminutes",
    "On XFGs",
    "H2H",
    "Home/Away on Xmins",
    "Line",
    "Stat",
    "Odds",
    "Result",
}

# Αντιστοίχιση ονομάτων στηλών του Sheet → εσωτερικά ονόματα στατιστικών.
SHEET_STAT_COLUMNS: dict[str, str] = {
    "Last 5": "Last 5",
    "On Xminutes": "On Xminutes",
    "On XFGs": "On XFGs",
    "H2H": "H2H",
    "Home/Away on Xmins": "Home/Away on Xmins",
}

# Αν μια στήλη έχει μόνο ποσοστό χωρίς (N), π.χ. Last 5 = "100%",
# χρησιμοποιούμε αυτό το προεπιλεγμένο δείγμα.
DEFAULT_N_BY_STAT: dict[str, int] = {
    "Last 5": 5,
}


# ---------------------------------------------------------------------------
# Ρυθμίσεις μοντέλου
# ---------------------------------------------------------------------------

BASE_WEIGHTS: dict[str, float] = {
    "On Xminutes": 0.30,
    "Last 5": 0.25,
    "Home/Away on Xmins": 0.20,
    "On XFGs": 0.15,
    "H2H": 0.10,
}

DEFAULT_K = 5.0

# Ποντάρισμα (euros)
DEFAULT_BANKROLL = 100.0          # αρχική μπάνκα
KELLY_FRACTION = 0.25             # Quarter-Kelly (1/4)
MAX_STAKE_BANKROLL_PCT = 0.05     # ανώτατο όριο: 5% της μπάνκας (= 5€ στα 100€)

# "64% (14)" ή σκέτο "100%" (το N είναι προαιρετικό)
STAT_PATTERN = re.compile(
    r"""
    (?P<pct>-?\d+(?:[.,]\d+)?)\s*%
    (?:\s*\(\s*(?P<n>\d+)\s*\))?
    """,
    re.VERBOSE,
)


# ---------------------------------------------------------------------------
# Δομές δεδομένων
# ---------------------------------------------------------------------------

@dataclass
class ParsedStat:
    name: str
    raw_input: str
    p: float
    n: int
    base_weight: float
    n_was_inferred: bool = False


@dataclass
class StatResult:
    name: str
    raw_input: str
    p: float
    n: int
    base_weight: float
    z: float
    raw_weight: float
    final_weight: float
    contribution: float
    n_was_inferred: bool = False


@dataclass
class StakePlan:
    """Quarter-Kelly ποντάρισμα σε ευρώ, με cap 5% της μπάνκας."""

    bankroll: float
    ev: float  # (p * odds) - 1, ως δεκαδικό (0.185 = +18.5%)
    kelly_full: float
    kelly_used: float  # Quarter-Kelly πριν το cap
    stake_pct: float  # τελικό ποσοστό μπάνκας μετά το cap
    stake_euros: float
    capped: bool
    no_bet: bool
    reason: str


@dataclass
class ModelOutput:
    stats: list[StatResult]
    k: float
    sum_raw_weight: float
    final_probability: float
    odds: Optional[float] = None
    implied_probability: Optional[float] = None
    expected_value_pct: Optional[float] = None
    is_value_bet: Optional[bool] = None
    stake: Optional[StakePlan] = None
    notes: list[str] = field(default_factory=list)


@dataclass
class SheetRow:
    """Μία γραμμή από το Google Sheet."""

    player: str
    line: str
    stat: str
    odds: Optional[float]
    result: str
    inputs: dict[str, str]
    source_row: int


@dataclass
class PlayerEval:
    row: SheetRow
    model: Optional[ModelOutput]
    error: Optional[str] = None


class ParseError(ValueError):
    """Αποτυχία parsing ενός string στατιστικού."""


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------

def _to_float(value: object) -> Optional[float]:
    if value is None:
        return None
    text = str(value).strip().replace(",", ".")
    if not text:
        return None
    try:
        return float(text)
    except ValueError as exc:
        raise ParseError(f"Μη έγκυρος αριθμός '{value}'.") from exc


def parse_stat_string(
    text: str,
    default_n: Optional[int] = None,
) -> tuple[float, int, bool]:
    """Εξάγει (P, N, n_was_inferred) από '64% (14)' ή '100%'.

    Το P επιστρέφεται στο [0, 1].
    Αν λείπει το (N) και υπάρχει default_n, το χρησιμοποιεί.
    """
    if text is None:
        raise ParseError("Κενό στατιστικό (None).")

    cleaned = str(text).strip()
    if not cleaned:
        raise ParseError("Κενό στατιστικό.")

    match = STAT_PATTERN.search(cleaned)
    if not match:
        raise ParseError(
            f"Μη έγκυρη μορφή '{text}'. Αναμένεται π.χ. '64% (14)' ή '100%'."
        )

    pct_str = match.group("pct").replace(",", ".")
    p = float(pct_str) / 100.0
    n_raw = match.group("n")
    inferred = False

    if n_raw is not None:
        n = int(n_raw)
    elif default_n is not None:
        n = int(default_n)
        inferred = True
    else:
        raise ParseError(
            f"Λείπει το δείγμα N στο '{text}' και δεν υπάρχει προεπιλογή."
        )

    if n < 0:
        raise ParseError(f"Αρνητικό δείγμα N={n} στο '{text}'.")

    return p, n, inferred


def parse_inputs(
    inputs: dict[str, str],
    base_weights: dict[str, float] = BASE_WEIGHTS,
    skip_invalid: bool = False,
) -> tuple[list[ParsedStat], list[str]]:
    """Μετατρέπει strings σε ParsedStat. Κενά πεδία παραλείπονται."""
    parsed: list[ParsedStat] = []
    notes: list[str] = []

    for name, raw in inputs.items():
        if name not in base_weights:
            raise KeyError(
                f"Άγνωστο στατιστικό '{name}'. "
                f"Επιτρεπτά: {', '.join(base_weights)}"
            )
        if raw is None or str(raw).strip() == "":
            notes.append(f"Παραλείφθηκε '{name}': κενό πεδίο.")
            continue
        try:
            p, n, inferred = parse_stat_string(
                raw, default_n=DEFAULT_N_BY_STAT.get(name)
            )
        except ParseError as exc:
            if skip_invalid:
                notes.append(f"Παραλείφθηκε '{name}': {exc}")
                continue
            raise

        if inferred:
            notes.append(
                f"Το '{name}' δεν είχε (N) στο sheet — χρησιμοποιήθηκε N={n}."
            )

        parsed.append(
            ParsedStat(
                name=name,
                raw_input=str(raw).strip(),
                p=p,
                n=n,
                base_weight=base_weights[name],
                n_was_inferred=inferred,
            )
        )
    return parsed, notes


# ---------------------------------------------------------------------------
# Credibility & στάθμιση
# ---------------------------------------------------------------------------

def credibility_factor(n: int, k: float = DEFAULT_K) -> float:
    """Bühlmann Z = N / (N + K). Συνεχής συνάρτηση, χωρίς thresholds."""
    if k < 0:
        raise ValueError("Το K πρέπει να είναι >= 0.")
    if n < 0:
        raise ValueError("Το N πρέπει να είναι >= 0.")
    denom = n + k
    if denom == 0:
        return 0.0
    return n / denom


def compute_stake(
    p: float,
    odds: float,
    bankroll: float = DEFAULT_BANKROLL,
    kelly_fraction: float = KELLY_FRACTION,
    max_stake_pct: float = MAX_STAKE_BANKROLL_PCT,
) -> StakePlan:
    """EV + Quarter-Kelly stake σε ευρώ.

    EV = (p * Odds) - 1
    Αν EV <= 0 → NO BET.
    Αλλιώς f_kelly = EV / (Odds - 1), stake = bankroll * (1/4) * f_kelly,
    με ανώτατο όριο max_stake_pct * bankroll (5€ στα 100€).
    """
    if bankroll <= 0:
        raise ValueError("Η μπάνκα πρέπει να είναι > 0.")
    if odds <= 1.0:
        raise ValueError("Οι αποδόσεις (odds) πρέπει να είναι > 1.0.")

    ev = (p * odds) - 1.0
    max_euros = round(bankroll * max_stake_pct, 2)

    if ev <= 0:
        return StakePlan(
            bankroll=bankroll,
            ev=ev,
            kelly_full=0.0,
            kelly_used=0.0,
            stake_pct=0.0,
            stake_euros=0.0,
            capped=False,
            no_bet=True,
            reason="NO BET (Δεν υπάρχει αξία/Value)",
        )

    # Decimal-odds Kelly: f* = (p*b - q) / b  με b = odds-1, q = 1-p
    # Ισοδύναμα: f* = EV / (odds - 1)
    kelly_full = ev / (odds - 1.0)
    kelly_full = max(0.0, min(kelly_full, 1.0))
    kelly_used = kelly_fraction * kelly_full
    stake_pct = kelly_used
    stake_euros = bankroll * stake_pct
    capped = False

    if stake_euros > max_euros:
        stake_euros = max_euros
        stake_pct = max_euros / bankroll
        capped = True

    return StakePlan(
        bankroll=bankroll,
        ev=ev,
        kelly_full=kelly_full,
        kelly_used=kelly_used,
        stake_pct=stake_pct,
        stake_euros=round(stake_euros, 2),
        capped=capped,
        no_bet=False,
        reason=(
            f"Quarter-Kelly capped at {max_euros:.2f} EUR "
            f"({max_stake_pct:.0%} bankroll)"
            if capped
            else "Quarter-Kelly (1/4)"
        ),
    )


def compute_weighted_probability(
    parsed: Iterable[ParsedStat],
    k: float = DEFAULT_K,
    odds: Optional[float] = None,
    extra_notes: Optional[list[str]] = None,
    bankroll: float = DEFAULT_BANKROLL,
) -> ModelOutput:
    parsed_list = list(parsed)
    notes: list[str] = list(extra_notes or [])

    if not parsed_list:
        raise ValueError("Δεν δόθηκαν στατιστικά.")

    z_list = [credibility_factor(s.n, k) for s in parsed_list]
    raw_weights = [s.base_weight * z for s, z in zip(parsed_list, z_list)]
    sum_raw = sum(raw_weights)

    if sum_raw <= 0:
        raise ValueError(
            "Άθροισμα raw weights = 0. Ελέγξτε δείγματα N και παράμετρο K."
        )

    final_weights = [w / sum_raw for w in raw_weights]
    contributions = [fw * s.p for fw, s in zip(final_weights, parsed_list)]
    final_p = sum(contributions)

    results = [
        StatResult(
            name=s.name,
            raw_input=s.raw_input,
            p=s.p,
            n=s.n,
            base_weight=s.base_weight,
            z=z,
            raw_weight=rw,
            final_weight=fw,
            contribution=c,
            n_was_inferred=s.n_was_inferred,
        )
        for s, z, rw, fw, c in zip(
            parsed_list, z_list, raw_weights, final_weights, contributions
        )
    ]

    implied = ev_pct = is_value = None
    stake: Optional[StakePlan] = None
    if odds is not None:
        if odds <= 1.0:
            raise ValueError("Οι αποδόσεις (odds) πρέπει να είναι > 1.0.")
        implied = 1.0 / odds
        ev_pct = (final_p * odds - 1.0) * 100.0
        is_value = ev_pct > 0
        if math.isclose(final_p, implied, rel_tol=1e-9, abs_tol=1e-12):
            is_value = False
            notes.append("Fair price (μοντέλο ≈ implied).")
        stake = compute_stake(final_p, odds, bankroll=bankroll)
        if stake.no_bet:
            is_value = False
        if stake.capped:
            notes.append(stake.reason)

    return ModelOutput(
        stats=results,
        k=k,
        sum_raw_weight=sum_raw,
        final_probability=final_p,
        odds=odds,
        implied_probability=implied,
        expected_value_pct=ev_pct,
        is_value_bet=is_value,
        stake=stake,
        notes=notes,
    )


def run_model(
    inputs: dict[str, str],
    odds: Optional[float] = None,
    k: float = DEFAULT_K,
    base_weights: dict[str, float] = BASE_WEIGHTS,
    skip_invalid: bool = True,
    bankroll: float = DEFAULT_BANKROLL,
) -> ModelOutput:
    parsed, notes = parse_inputs(
        inputs, base_weights=base_weights, skip_invalid=skip_invalid
    )
    return compute_weighted_probability(
        parsed, k=k, odds=odds, extra_notes=notes, bankroll=bankroll
    )


# ---------------------------------------------------------------------------
# Google Sheet fetch
# ---------------------------------------------------------------------------

def _http_get_text(url: str, timeout: int = 30) -> str:
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "Mozilla/5.0 (Fermabets Euroleague Props)"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
    return raw.decode("utf-8-sig")


def fetch_sheet_csv(timeout: int = 30) -> str:
    """Κατεβάζει το φύλλο ως CSV. Δοκιμάζει export και μετά gviz."""
    errors: list[str] = []
    for url in (SHEET_CSV_URL, SHEET_GVIZ_CSV_URL):
        try:
            return _http_get_text(url, timeout=timeout)
        except urllib.error.URLError as exc:
            errors.append(f"{url}: {exc}")
    raise RuntimeError(
        "Αποτυχία ανάγνωσης του Google Sheet. "
        "Βεβαιώσου ότι είναι shared ως 'Anyone with the link can view'. "
        + " | ".join(errors)
    )


def _norm_header(name: str) -> str:
    return re.sub(r"\s+", " ", str(name).strip())


def parse_sheet_rows(csv_text: str) -> list[SheetRow]:
    """Μετατρέπει το CSV του Sheet σε λίστα SheetRow (αγνοεί κενές γραμμές)."""
    reader = csv.DictReader(io.StringIO(csv_text))
    if not reader.fieldnames:
        raise ValueError("Το Sheet δεν έχει γραμμή επικεφαλίδων.")

    header_map = {_norm_header(h): h for h in reader.fieldnames if h}
    required = ["Player", *SHEET_STAT_COLUMNS]
    missing = [c for c in required if c not in header_map]
    if missing:
        raise ValueError(
            "Λείπουν στήλες από το Sheet: "
            + ", ".join(missing)
            + f". Βρέθηκαν: {', '.join(header_map)}"
        )

    rows: list[SheetRow] = []
    for i, raw in enumerate(reader, start=2):  # γραμμή 1 = headers
        player = str(raw.get(header_map["Player"]) or "").strip()
        if not player:
            continue

        inputs: dict[str, str] = {}
        for sheet_col, stat_name in SHEET_STAT_COLUMNS.items():
            inputs[stat_name] = str(raw.get(header_map[sheet_col]) or "").strip()

        odds_cell = raw.get(header_map["Odds"]) if "Odds" in header_map else ""
        line_cell = raw.get(header_map["Line"]) if "Line" in header_map else ""
        stat_cell = raw.get(header_map["Stat"]) if "Stat" in header_map else ""
        result_cell = raw.get(header_map["Result"]) if "Result" in header_map else ""

        odds_val = None
        if odds_cell not in (None, ""):
            try:
                odds_val = _to_float(odds_cell)
            except ParseError:
                odds_val = None

        rows.append(
            SheetRow(
                player=player,
                line=str(line_cell or "").strip(),
                stat=str(stat_cell or "").strip(),
                odds=odds_val,
                result=str(result_cell or "").strip(),
                inputs=inputs,
                source_row=i,
            )
        )
    return rows


def load_sheet_rows() -> list[SheetRow]:
    return parse_sheet_rows(fetch_sheet_csv())


def evaluate_sheet_row(
    row: SheetRow,
    k: float = DEFAULT_K,
    bankroll: float = DEFAULT_BANKROLL,
) -> PlayerEval:
    try:
        model = run_model(
            row.inputs,
            odds=row.odds,
            k=k,
            skip_invalid=True,
            bankroll=bankroll,
        )
        return PlayerEval(row=row, model=model)
    except Exception as exc:  # noqa: BLE001 — μία αποτυχία δεν σταματά τις υπόλοιπες γραμμές
        return PlayerEval(row=row, model=None, error=str(exc))


# ---------------------------------------------------------------------------
# Εκτύπωση
# ---------------------------------------------------------------------------

def _pct(x: float, digits: int = 1) -> str:
    return f"{x * 100:.{digits}f}%"


def _bar(weight: float, width: int = 16) -> str:
    filled = int(round(max(0.0, min(1.0, weight)) * width))
    return "#" * filled + "-" * (width - filled)


def format_report(out: ModelOutput, title: str = "Euroleague Player Props") -> str:
    col = [
        ("Statistiko", 22),
        ("Input", 14),
        ("P", 7),
        ("N", 4),
        ("Z", 6),
        ("Base W", 8),
        ("Raw W", 8),
        ("Final W", 9),
        ("Contrib", 8),
    ]

    def header() -> str:
        return "  ".join(name.ljust(w) for name, w in col)

    sep = "-" * len(header())
    lines: list[str] = [
        "=" * len(sep),
        title,
        f"Buhlmann K = {out.k:g}    Z_i = N_i / (N_i + K)",
        "=" * len(sep),
        header(),
        sep,
    ]

    for s in out.stats:
        n_mark = "*" if s.n_was_inferred else " "
        row = [
            s.name.ljust(col[0][1]),
            s.raw_input.ljust(col[1][1]),
            _pct(s.p).rjust(col[2][1]),
            (str(s.n) + n_mark).rjust(col[3][1]),
            f"{s.z:.3f}".rjust(col[4][1]),
            _pct(s.base_weight).rjust(col[5][1]),
            f"{s.raw_weight:.4f}".rjust(col[6][1]),
            _pct(s.final_weight).rjust(col[7][1]),
            _pct(s.contribution).rjust(col[8][1]),
        ]
        lines.append("  ".join(row))
        lines.append(
            " " * (col[0][1] + 2)
            + _bar(s.final_weight)
            + f"  (Z={s.z:.1%} of base weight)"
        )

    lines.append(sep)
    lines.append(
        f"{'TOTAL'.ljust(col[0][1])}  "
        f"{''.ljust(col[1][1])}  "
        f"{''.rjust(col[2][1])}  "
        f"{''.rjust(col[3][1])}  "
        f"{''.rjust(col[4][1])}  "
        f"{_pct(sum(s.base_weight for s in out.stats)).rjust(col[5][1])}  "
        f"{f'{out.sum_raw_weight:.4f}'.rjust(col[6][1])}  "
        f"{_pct(sum(s.final_weight for s in out.stats)).rjust(col[7][1])}  "
        f"{_pct(out.final_probability).rjust(col[8][1])}"
    )
    lines.append("=" * len(sep))
    lines.append(
        f"Τελική Πιθανότητα Μοντέλου (%):    {_pct(out.final_probability, 1)}"
    )

    if out.odds is not None and out.implied_probability is not None:
        ev = out.expected_value_pct if out.expected_value_pct is not None else 0.0
        sign = "+" if ev >= 0 else ""
        lines.append(
            f"Implied Probability Μπουκ (%):     {_pct(out.implied_probability, 1)}"
        )
        lines.append(f"Expected Value (%):                 {sign}{ev:.1f}%")
        lines.append(f"Odds:                               {out.odds:.2f}")
        lines.extend(_format_stake_lines(out.stake))

    if any(s.n_was_inferred for s in out.stats):
        lines.append("  * N inferred (sheet had % without sample size)")

    if out.notes:
        lines.append("")
        lines.append("Notes:")
        for note in out.notes:
            lines.append(f"  - {note}")

    lines.append("=" * len(sep))
    return "\n".join(lines)


def _format_stake_lines(stake: Optional[StakePlan]) -> list[str]:
    if stake is None:
        return ["ΠΡΟΤΕΙΝΟΜΕΝΟ ΠΟΝΤΑΡΙΣΜΑ:            n/a (λείπουν odds)"]
    bank = stake.bankroll
    if stake.no_bet:
        return [
            f"ΠΡΟΤΕΙΝΟΜΕΝΟ ΠΟΝΤΑΡΙΣΜΑ:            0.00 EUR (από τα {bank:.0f} EUR)",
            f"Απόφαση:                            {stake.reason}",
        ]
    cap_note = " [CAP 5%]" if stake.capped else ""
    return [
        f"Kelly (full):                      {stake.kelly_full:.1%}",
        f"Quarter-Kelly (1/4):               {stake.kelly_used:.1%}"
        + cap_note,
        f"ΠΡΟΤΕΙΝΟΜΕΝΟ ΠΟΝΤΑΡΙΣΜΑ:            {stake.stake_euros:.2f} EUR "
        f"(από τα {bank:.0f} EUR)",
    ]


def format_player_eval(ev: PlayerEval) -> str:
    row = ev.row
    line_txt = row.line or "-"
    stat_txt = row.stat or "-"
    title = f"{row.player}  |  {stat_txt} {line_txt}  |  sheet row {row.source_row}"
    if ev.error or ev.model is None:
        return f"{title}\nERROR: {ev.error}"
    return format_report(ev.model, title=title)


def _stake_cell(m: Optional[ModelOutput]) -> str:
    if m is None or m.stake is None:
        return "-"
    if m.stake.no_bet:
        return "NO BET"
    extra = "*" if m.stake.capped else ""
    return f"{m.stake.stake_euros:.2f}e{extra}"


def format_summary_table(evals: list[PlayerEval]) -> str:
    headers = (
        "Player",
        "Stat",
        "Line",
        "Odds",
        "Model%",
        "Implied",
        "EV%",
        "Stake",
    )
    widths = [22, 8, 6, 6, 8, 8, 8, 10]

    def fmt_row(vals: list[str]) -> str:
        return "  ".join(v[:w].ljust(w) for v, w in zip(vals, widths))

    bank = DEFAULT_BANKROLL
    if evals and evals[0].model and evals[0].model.stake:
        bank = evals[0].model.stake.bankroll

    lines = [
        "=" * 90,
        f"SUMMARY (from Google Sheet)  |  bankroll {bank:.0f} EUR  |  Quarter-Kelly, cap 5%",
        SHEET_URL,
        "=" * 90,
        fmt_row(list(headers)),
        "-" * 90,
    ]
    for ev in evals:
        r = ev.row
        if ev.model is None:
            lines.append(
                fmt_row(
                    [
                        r.player,
                        r.stat or "-",
                        f"{r.line or '-'}",
                        f"{r.odds:.2f}" if r.odds is not None else "-",
                        "ERR",
                        "-",
                        "-",
                        ev.error or "error",
                    ]
                )
            )
            continue
        m = ev.model
        ev_txt = (
            f"{m.expected_value_pct:+.1f}%"
            if m.expected_value_pct is not None
            else "-"
        )
        implied_txt = (
            _pct(m.implied_probability, 1)
            if m.implied_probability is not None
            else "-"
        )
        lines.append(
            fmt_row(
                [
                    r.player,
                    r.stat or "-",
                    r.line or "-",
                    f"{r.odds:.2f}" if r.odds is not None else "-",
                    _pct(m.final_probability, 1),
                    implied_txt,
                    ev_txt,
                    _stake_cell(m),
                ]
            )
        )
    lines.append("=" * 90)
    return "\n".join(lines)


def _eu_pct(x: float, digits: int = 2, signed: bool = False) -> str:
    """Μορφή φύλλου: 77,44% ή +47,13%."""
    val = x * 100.0
    sign = "+" if signed and val > 0 else ""
    return f"{sign}{val:.{digits}f}%".replace(".", ",")


def _eu_num(x: float, digits: int = 2) -> str:
    return f"{x:.{digits}f}".replace(".", ",")


def sheet_values_for_eval(ev: PlayerEval) -> dict[str, str]:
    """Μόνο Model Probability και Edge. Τα άλλα πεδία του Sheet μένουν άθικτα."""
    if ev.model is None:
        return {"Model Probability": "", "Edge": ""}

    m = ev.model
    values = {
        "Model Probability": _eu_pct(m.final_probability, 2),
        "Edge": "",
    }
    if m.implied_probability is not None:
        values["Edge"] = _eu_pct(m.final_probability - m.implied_probability, 2)
    return values


def _post_json_follow(url: str, payload: dict, timeout: int = 30) -> str:
    """POST JSON· ακολουθεί τα 302 του Apps Script Web App κρατώντας το body."""
    data = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    current = url
    for _ in range(6):
        req = urllib.request.Request(current, data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            if exc.code in (301, 302, 303, 307, 308):
                loc = exc.headers.get("Location")
                if not loc:
                    raise
                current = loc
                continue
            raise
    raise RuntimeError("Πάρα πολλά redirects στο Apps Script Web App.")


def push_sheet_via_webapp(evals: list[PlayerEval]) -> str:
    if not SHEET_WEBAPP_URL:
        raise RuntimeError("SHEET_WEBAPP_URL κενό.")
    updates = [
        {
            "row": ev.row.source_row,
            "values": sheet_values_for_eval(ev),
        }
        for ev in evals
    ]
    raw = _post_json_follow(
        SHEET_WEBAPP_URL,
        {"updates": updates},
    )
    return raw


def push_sheet_via_gspread(evals: list[PlayerEval]) -> str:
    """Γράφει με service account (service_account.json) αν υπάρχει."""
    if not os.path.exists(SERVICE_ACCOUNT_FILE):
        raise RuntimeError("Δεν βρέθηκε service_account.json.")
    try:
        import gspread
        from google.oauth2.service_account import Credentials
    except ImportError as exc:
        raise RuntimeError(
            "Λείπουν gspread/google-auth. "
            "Τρέξε: pip install gspread google-auth"
        ) from exc

    scopes = ["https://www.googleapis.com/auth/spreadsheets"]
    creds = Credentials.from_service_account_file(SERVICE_ACCOUNT_FILE, scopes=scopes)
    gc = gspread.authorize(creds)
    ws = gc.open_by_key(SHEET_ID).get_worksheet(0)
    headers = [_norm_header(h) for h in ws.row_values(1)]
    header_set = set(headers)
    for name in COMPUTED_SHEET_COLUMNS:
        if name not in header_set:
            col = len(headers) + 1
            ws.update_cell(1, col, name)
            headers.append(name)
            header_set.add(name)
    col_index = {h: i + 1 for i, h in enumerate(headers)}

    for ev in evals:
        values = sheet_values_for_eval(ev)
        for name, val in values.items():
            if name in PROTECTED_COLUMNS:
                continue
            idx = col_index.get(name)
            if idx:
                ws.update_cell(ev.row.source_row, idx, val)
    return f"gspread wrote {len(evals)} rows"


def push_sheet_updates(evals: list[PlayerEval]) -> str:
    """Προσπαθεί Web App και μετά service account. Δεν αγγίζει Result."""
    errors: list[str] = []
    if SHEET_WEBAPP_URL:
        try:
            return "webapp: " + push_sheet_via_webapp(evals)
        except Exception as exc:  # noqa: BLE001
            errors.append(f"webapp: {exc}")
    if os.path.exists(SERVICE_ACCOUNT_FILE):
        try:
            return push_sheet_via_gspread(evals)
        except Exception as exc:  # noqa: BLE001
            errors.append(f"gspread: {exc}")
    hint = (
        "Δεν μπόρεσα να γράψω στο Google Sheet (το Result δεν πειράζεται έτσι κι αλλιώς).\n"
        "Γρήγορη λύση: άνοιξε το Sheet → Extensions → Apps Script → "
        "επικόλλησε το google_sheets_bridge.gs → Save → "
        "τρέξε fillComputedColumns, ή Deploy Web app και βάλε το URL στο SHEET_WEBAPP_URL.\n"
        "Εναλλακτικά: μοίρασε το Sheet ως Editor στο service account και βάλε service_account.json."
    )
    if errors:
        hint += "\n" + " | ".join(errors)
    raise RuntimeError(hint)


def write_local_results(evals: list[PlayerEval], path: str) -> None:
    """Αποθηκεύει αποτελέσματα τοπικά (το View-only Sheet δεν γράφεται πίσω)."""
    fieldnames = [
        "Player",
        "Stat",
        "Line",
        "Odds",
        "Model_Hit_%",
        "Implied_%",
        "EV_%",
        "Stake_EUR",
        "Kelly_full_%",
        "Quarter_Kelly_%",
        "Capped",
        "Verdict",
        "Sheet_Result",
        "Error",
    ]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for ev in evals:
            r = ev.row
            m = ev.model
            writer.writerow(
                {
                    "Player": r.player,
                    "Stat": r.stat,
                    "Line": r.line,
                    "Odds": r.odds,
                    "Model_Hit_%": (
                        round(m.final_probability * 100, 2) if m else ""
                    ),
                    "Implied_%": (
                        round(m.implied_probability * 100, 2)
                        if m and m.implied_probability is not None
                        else ""
                    ),
                    "EV_%": (
                        round(m.expected_value_pct, 2)
                        if m and m.expected_value_pct is not None
                        else ""
                    ),
                    "Stake_EUR": (
                        m.stake.stake_euros
                        if m and m.stake and not m.stake.no_bet
                        else (0 if m and m.stake and m.stake.no_bet else "")
                    ),
                    "Kelly_full_%": (
                        round(m.stake.kelly_full * 100, 2)
                        if m and m.stake
                        else ""
                    ),
                    "Quarter_Kelly_%": (
                        round(m.stake.kelly_used * 100, 2)
                        if m and m.stake
                        else ""
                    ),
                    "Capped": (
                        "YES"
                        if m and m.stake and m.stake.capped
                        else ("NO" if m and m.stake else "")
                    ),
                    "Verdict": (
                        m.stake.reason
                        if m and m.stake and m.stake.no_bet
                        else (
                            "VALUE BET"
                            if m and m.is_value_bet
                            else (
                                "NO VALUE"
                                if m and m.is_value_bet is False
                                else ""
                            )
                        )
                    ),
                    "Sheet_Result": r.result,
                    "Error": ev.error or "",
                }
            )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _configure_stdout() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def _build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=(
            "Euroleague Player Props από το Google Sheet, "
            "με δυναμικές Buhlmann βαρύτητες."
        )
    )
    p.add_argument(
        "--sheet",
        action="store_true",
        default=True,
        help="Τράβα δεδομένα από το Google Sheet (προεπιλογή).",
    )
    p.add_argument(
        "--player",
        default="",
        help="Φίλτρο ονόματος παίκτη (substring, case-insensitive).",
    )
    p.add_argument("--k", type=float, default=DEFAULT_K, help="Buhlmann K (default 5)")
    p.add_argument(
        "--bankroll",
        type=float,
        default=DEFAULT_BANKROLL,
        help="Μπάνκα σε ευρώ (default: 100)",
    )
    p.add_argument(
        "--demo",
        action="store_true",
        help="Τρέξε το έτοιμο παράδειγμα (Andres Feliz) χωρίς Sheet.",
    )
    p.add_argument(
        "--out",
        default="euroleague_results.csv",
        help="Τοπικό CSV αποτελεσμάτων (default: euroleague_results.csv).",
    )
    p.add_argument(
        "--no-detail",
        action="store_true",
        help="Τύπωσε μόνο τον συνοπτικό πίνακα.",
    )
    p.add_argument(
        "--no-push",
        action="store_true",
        help="Μην γράψεις πίσω στο Google Sheet.",
    )
    return p


EXAMPLE_INPUTS: dict[str, str] = {
    "Last 5": "100% (5)",
    "On Xminutes": "64% (14)",
    "On XFGs": "89% (9)",
    "H2H": "100% (1)",
    "Home/Away on Xmins": "67% (9)",
}
EXAMPLE_ODDS = 1.90


def run_example(
    k: float = DEFAULT_K,
    bankroll: float = DEFAULT_BANKROLL,
) -> ModelOutput:
    """Έτοιμο παράδειγμα: Andres Feliz Pts 7.5 @ 1.90, μπάνκα 100€."""
    return run_model(EXAMPLE_INPUTS, odds=EXAMPLE_ODDS, k=k, bankroll=bankroll)


def main() -> None:
    _configure_stdout()
    args = _build_arg_parser().parse_args()

    if args.demo:
        out = run_example(k=args.k, bankroll=args.bankroll)
        print(
            format_report(
                out,
                title="DEMO  |  Andres Feliz  |  Pts 7.5  |  Odds 1.90",
            )
        )
        return

    print(f"Fetching Google Sheet...\n{SHEET_URL}\n")
    rows = load_sheet_rows()
    if args.player:
        needle = args.player.strip().lower()
        rows = [r for r in rows if needle in r.player.lower()]

    if not rows:
        print("Δεν βρέθηκαν γραμμές παικτών στο Sheet.")
        return

    evals = [
        evaluate_sheet_row(r, k=args.k, bankroll=args.bankroll) for r in rows
    ]
    print(format_summary_table(evals))
    print()

    if not args.no_detail:
        for ev in evals:
            print(format_player_eval(ev))
            print()

    write_local_results(evals, args.out)
    print(f"Saved local results: {args.out}")

    if args.no_push:
        print("Skip sheet push (--no-push). Result δεν γράφεται ποτέ.")
        return

    try:
        msg = push_sheet_updates(evals)
        print(f"Google Sheet updated: {msg}")
        print("Στήλη Result: άθικτη (τη συμπληρώνεις εσύ μετά).")
    except Exception as exc:  # noqa: BLE001
        print(str(exc))


if __name__ == "__main__":
    main()
