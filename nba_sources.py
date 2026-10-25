"""Basketball-Reference player logs plus pbpstats team pace for the NBA tab.

Euroleague still uses Hackastat. This module is only called by run_nba_daily.py.
A player cell may hold one or two gamelog links (one season each). Pace is joined
by date from the pbpstats team link the user pastes in Exp Pace.
"""

from __future__ import annotations

import json
import re
import urllib.error
import urllib.request
from io import StringIO
from typing import Optional
from urllib.parse import parse_qs, urlencode, urlparse

import pandas as pd

from hackastat_scraper import clean_game_log, parse_match_date

_UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}

# Basketball-Reference codes. Alternate codes (pbpstats and older BBR) fold in.
_NAMES = {
    "ATL": "Atlanta Hawks",
    "BOS": "Boston Celtics",
    "BRK": "Brooklyn Nets",
    "CHO": "Charlotte Hornets",
    "CHI": "Chicago Bulls",
    "CLE": "Cleveland Cavaliers",
    "DAL": "Dallas Mavericks",
    "DEN": "Denver Nuggets",
    "DET": "Detroit Pistons",
    "GSW": "Golden State Warriors",
    "HOU": "Houston Rockets",
    "IND": "Indiana Pacers",
    "LAC": "Los Angeles Clippers",
    "LAL": "Los Angeles Lakers",
    "MEM": "Memphis Grizzlies",
    "MIA": "Miami Heat",
    "MIL": "Milwaukee Bucks",
    "MIN": "Minnesota Timberwolves",
    "NOP": "New Orleans Pelicans",
    "NYK": "New York Knicks",
    "OKC": "Oklahoma City Thunder",
    "ORL": "Orlando Magic",
    "PHI": "Philadelphia 76ers",
    "PHO": "Phoenix Suns",
    "POR": "Portland Trail Blazers",
    "SAC": "Sacramento Kings",
    "SAS": "San Antonio Spurs",
    "TOR": "Toronto Raptors",
    "UTA": "Utah Jazz",
    "WAS": "Washington Wizards",
}
_TO_BBR = {
    "BKN": "BRK",
    "CHA": "CHO",
    "PHX": "PHO",
    "GS": "GSW",
    "NO": "NOP",
    "NOH": "NOP",
    "NY": "NYK",
    "SA": "SAS",
    "WSH": "WAS",
    "UTAH": "UTA",
    "NJN": "BRK",
}


def player_urls(text: object) -> list[str]:
    """One or two basketball-reference gamelog links from a single cell."""
    found = re.findall(r"https?://[^\s]+", str(text or ""))
    urls = []
    for raw in found:
        url = raw.rstrip(").,;")
        if "basketball-reference.com" not in url:
            continue
        if "/gamelog-advanced/" in url:
            raise ValueError(
                "Paste the regular gamelog link (/gamelog/2026/), not the advanced page. "
                "Usage is read from the advanced page automatically."
            )
        if "/gamelog/" not in url:
            raise ValueError(
                "The player link must be a Basketball-Reference gamelog, "
                "for example /players/t/thompau01/gamelog/2026/"
            )
        if url not in urls:
            urls.append(url)
    if found and not urls:
        raise ValueError("NBA player link must be a basketball-reference.com gamelog.")
    return urls


def _get(url: str) -> str:
    req = urllib.request.Request(url, headers=_UA)
    try:
        with urllib.request.urlopen(req, timeout=45) as resp:
            return resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"Could not open {url} ({exc.code}).") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Could not open {url} ({exc.reason}).") from exc


def _tables(html: str) -> list[pd.DataFrame]:
    frames = pd.read_html(StringIO(html))
    if not any(_is_log(frame) for frame in frames):
        unwrapped = re.sub(r"<!--|-->", "", html)
        frames = pd.read_html(StringIO(unwrapped))
    return [frame for frame in frames if _is_log(frame)]


def _is_log(frame: pd.DataFrame) -> bool:
    cols = {str(c) for c in frame.columns}
    return "Date" in cols and ("MP" in cols or "USG%" in cols)


def _series(frame: pd.DataFrame, name: str) -> pd.Series:
    if name in frame.columns:
        return frame[name]
    return pd.Series([None] * len(frame), index=frame.index)


def _location_labels(frame: pd.DataFrame) -> pd.Series:
    """BBR puts @ in the column just left of Opp. Blank is home, N is neutral."""
    cols = list(frame.columns)
    if "Opp" not in cols:
        return pd.Series(["home"] * len(frame), index=frame.index)
    raw = frame.iloc[:, cols.index("Opp") - 1] if cols.index("Opp") else ""
    labels = []
    for value in raw:
        text = str(value or "").strip().lower()
        if text in {"@", "away"}:
            labels.append("away")
        elif text in {"n", "neutral"}:
            labels.append("neutral")
        else:
            labels.append("home")
    return pd.Series(labels, index=frame.index)


def canon_team(value: object) -> str:
    text = re.sub(r"[^A-Za-z]", " ", str(value or "")).upper()
    for part in text.split():
        code = _TO_BBR.get(part, part if part in _NAMES else "")
        if code:
            return code
    return ""


def label_opponent(code: object) -> str:
    """Keep the code and the full name so H2H matches Bulls, Chicago, or CHI."""
    key = str(code or "").strip().upper()
    if not key:
        return ""
    extras = [alias for alias, canonical in _TO_BBR.items() if canonical == key]
    name = _NAMES.get(key, "")
    parts = [key, *extras, name]
    seen = []
    for part in parts:
        if part and part not in seen:
            seen.append(part)
    return " ".join(seen)


def _one_season(url: str, html_cache: dict[str, str]) -> pd.DataFrame:
    html_cache.setdefault(url, _get(url))
    basic_tables = _tables(html_cache[url])
    if not basic_tables:
        raise RuntimeError(f"No game log table at {url}")
    basic = pd.concat(basic_tables, ignore_index=True)
    basic = basic.loc[basic["Date"].astype(str).str.contains(r"\d{4}-\d{2}-\d{2}", na=False)].copy()

    usg = pd.Series([None] * len(basic), index=basic.index)
    advanced_url = url.replace("/gamelog/", "/gamelog-advanced/")
    try:
        html_cache.setdefault(advanced_url, _get(advanced_url))
        adv_tables = _tables(html_cache[advanced_url])
    except RuntimeError:
        adv_tables = []
    if adv_tables:
        adv = pd.concat(adv_tables, ignore_index=True)
        adv = adv.loc[adv["Date"].astype(str).str.contains(r"\d{4}-\d{2}-\d{2}", na=False)].copy()
        adv["_date"] = adv["Date"].map(parse_match_date)
        adv["_opp"] = adv["Opp"].map(canon_team)
        adv = adv.dropna(subset=["_date"])
        adv = adv.loc[adv["_opp"] != ""]
        lookup = adv.groupby(["_date", "_opp"])["USG%"].first() if "USG%" in adv.columns else None
        if lookup is not None:
            keys = list(zip(basic["Date"].map(parse_match_date), basic["Opp"].map(canon_team)))
            usg = pd.Series(
                [lookup[key] if key in lookup.index else None for key in keys],
                index=basic.index,
            )

    frame = pd.DataFrame(
        {
            "date": basic["Date"].map(parse_match_date),
            "opp": basic["Opp"].map(canon_team),
            "loc": _location_labels(basic).to_numpy(),
            "min": _series(basic, "MP").to_numpy(),
            "fga": _series(basic, "FGA").to_numpy(),
            "fta": _series(basic, "FTA").to_numpy(),
            "3pm": _series(basic, "3P").to_numpy(),
            "3pa": _series(basic, "3PA").to_numpy(),
            "pts": _series(basic, "PTS").to_numpy(),
            "trb": _series(basic, "TRB").to_numpy(),
            "orb": _series(basic, "ORB").to_numpy(),
            "drb": _series(basic, "DRB").to_numpy(),
            "ast": _series(basic, "AST").to_numpy(),
            "usg": usg.to_numpy(),
        }
    )
    cleaned = clean_game_log(frame)
    cleaned = cleaned.loc[cleaned["date"].map(parse_match_date).notna()].copy()
    if "min" in cleaned.columns:
        cleaned = cleaned.loc[cleaned["min"].notna()].copy()
    cleaned = cleaned.loc[cleaned["opp"].astype(str).str.len() > 0].copy()
    if "usg" in cleaned.columns:
        med = pd.to_numeric(cleaned["usg"], errors="coerce").median()
        if pd.notna(med) and float(med) <= 1.5:
            cleaned["usg"] = cleaned["usg"] * 100.0
    return cleaned.reset_index(drop=True)


def fetch_player_log(url_text: object, html_cache: Optional[dict[str, str]] = None) -> pd.DataFrame:
    urls = player_urls(url_text)
    if not urls:
        raise ValueError("Basketball-Reference URL is blank, and this player has no earlier link.")
    cache = html_cache if html_cache is not None else {}
    frames = [_one_season(url, cache) for url in urls]
    games = pd.concat(frames, ignore_index=True)
    games = games.drop_duplicates(subset=["date", "opp"], keep="last")
    games = games.sort_values("date").reset_index(drop=True)
    if games.empty:
        raise RuntimeError("The gamelog links have no played games.")
    return games


def _pace_requests(pace_url: str) -> list[str]:
    parsed = urlparse(pace_url.strip())
    host = (parsed.netloc or "").lower()
    if "pbpstats.com" not in host:
        raise ValueError("NBA pace link must be a pbpstats.com team game log.")
    query = parse_qs(parsed.query)
    if "api.pbpstats.com" in host and parsed.path.startswith("/get-game-logs/"):
        return [pace_url]
    entity = (query.get("EntityId") or query.get("entityid") or [""])[0].strip()
    if not entity:
        raise ValueError("The pbpstats link needs an EntityId for the player's team.")
    seasons = (query.get("Season") or ["2025-26"])[0]
    season_list = [part.strip() for part in seasons.split(",") if part.strip()]
    season_type = (query.get("SeasonType") or ["All"])[0].replace("+", " ").strip()
    if season_type.lower() in {"", "all"}:
        types = ["Regular Season", "Playoffs"]
    else:
        types = [season_type]
    urls = []
    for season in season_list:
        for kind in types:
            qs = urlencode(
                {
                    "Season": season,
                    "SeasonType": kind,
                    "EntityType": "Team",
                    "EntityId": entity,
                }
            )
            urls.append(f"https://api.pbpstats.com/get-game-logs/nba?{qs}")
    return urls


def fetch_team_pace(pace_url: str, pace_cache: Optional[dict[str, pd.DataFrame]] = None) -> pd.DataFrame:
    """Team pace rows with date, opp code, and pace. Empty seasons are skipped."""
    if pace_cache is not None and pace_url in pace_cache:
        return pace_cache[pace_url].copy()
    rows: list[dict[str, object]] = []
    errors: list[str] = []
    for api_url in _pace_requests(pace_url):
        try:
            payload = json.loads(_get(api_url))
        except RuntimeError as exc:
            errors.append(str(exc))
            continue
        table = payload.get("multi_row_table_data") if isinstance(payload, dict) else None
        if not isinstance(table, list):
            continue
        for row in table:
            if not isinstance(row, dict):
                continue
            pace = row.get("Pace")
            date = parse_match_date(row.get("Date"))
            opp = canon_team(row.get("Opponent"))
            if date and opp and pace not in (None, ""):
                rows.append({"date": date, "opp": opp, "pace": pace})
    if not rows and errors:
        raise RuntimeError(errors[0])
    if not rows:
        raise RuntimeError("The pbpstats link returned no team games with pace.")
    frame = clean_game_log(pd.DataFrame(rows))
    if pace_cache is not None:
        pace_cache[pace_url] = frame.copy()
    return frame


def load_nba_games(
    url_text: object,
    pace_url: str = "",
    html_cache: Optional[dict[str, str]] = None,
    pace_cache: Optional[dict[str, pd.DataFrame]] = None,
) -> tuple[pd.DataFrame, int, list[str]]:
    """Player log with pace attached and opponents labeled for H2H."""
    from hackastat_scraper import attach_pace

    notes: list[str] = []
    games = fetch_player_log(url_text, html_cache=html_cache)
    urls = player_urls(url_text)
    notes.append(f"{len(urls)} season link(s), {len(games)} played games")
    if "usg" not in games.columns or games["usg"].notna().sum() == 0:
        notes.append("usage was not on the advanced log, so the usage filter is skipped")
    matched = 0
    if pace_url:
        pace = fetch_team_pace(pace_url, pace_cache=pace_cache)
        notes.append(f"pace log {len(pace)} team games")
        games, matched = attach_pace(games, pace, opponent_required=True)
    games["opp"] = games["opp"].map(label_opponent)
    return games, matched, notes
