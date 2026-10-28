"""RotoWire NBA lineups: injuries and the composite handicap (warnings only).

Usage: python rotowire_ingest.py --db ../data/fermabets.db
"""
from __future__ import annotations

import argparse
import datetime as dt
import re
import urllib.error
import urllib.request

from lxml import html as lxml_html

from common import connect, log, name_key

LEAGUE = "NBA"
LINEUPS_URLS = (
    "https://www.rotowire.com/basketball/nba-lineups.php",
    "https://www.rotowire.com/basketball/nba-lineups.php?date=tomorrow",
)
_UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36"}

# RotoWire / BBR abbreviations -> nba_api tricodes stored in schedule and player_game.
_TO_NBA = {
    "BRK": "BKN", "BKN": "BKN",
    "CHO": "CHA", "CHA": "CHA",
    "PHO": "PHX", "PHX": "PHX",
    "GS": "GSW", "GSW": "GSW",
    "NO": "NOP", "NOP": "NOP", "NOH": "NOP",
    "NY": "NYK", "NYK": "NYK",
    "SA": "SAS", "SAS": "SAS",
    "WSH": "WAS", "WAS": "WAS",
    "UTAH": "UTA", "UTA": "UTA",
    "NJN": "BKN",
}


def canon_nba(code: str) -> str:
    raw = (code or "").strip().upper()
    return _TO_NBA.get(raw, raw)


def _get(url: str) -> str:
    req = urllib.request.Request(url, headers=_UA)
    with urllib.request.urlopen(req, timeout=40) as resp:
        return resp.read().decode("utf-8", "replace")


def _text(node) -> str:
    return " ".join(node.text_content().split()) if node is not None else ""


def _first(nodes):
    return nodes[0] if nodes else None


def parse_side(box, cls: str) -> tuple[str, list[dict]]:
    team_link = _first(box.xpath(f".//a[contains(@class,'lineup__team') and contains(@class,'{cls}')]"))
    abbr = _text(_first(team_link.xpath(".//*[contains(@class,'lineup__abbr')]"))) if team_link is not None else ""
    code = canon_nba(abbr)
    players: list[dict] = []
    ul = _first(box.xpath(f".//ul[contains(@class,'lineup__list') and contains(@class,'{cls}')]"))
    if ul is None:
        return code, players
    starter = True
    for li in ul.xpath("./li"):
        classes = li.get("class", "")
        if "lineup__title" in classes:
            starter = False
            continue
        if "lineup__player" not in classes:
            continue
        link = _first(li.xpath(".//a"))
        name = (link.get("title") if link is not None else "") or _text(link)
        inj = _text(_first(li.xpath(".//*[contains(@class,'lineup__inj')]")))
        existing = next((p for p in players if p["name"] == name), None)
        if existing is not None:
            existing["status"] = existing["status"] or inj
            continue
        players.append({
            "name": name,
            "status": inj,
            "starter": starter and not inj,
        })
    return code, players


def parse_spread(box) -> tuple[str, float | None, float | None]:
    team, spread, total = "", None, None
    for item in box.xpath(".//*[contains(@class,'lineup__odds-item')]"):
        label = _text(_first(item.xpath("./b"))).upper()
        composite = _text(_first(item.xpath(".//span[contains(@class,'composite')]")))
        if not composite:
            spans = [_text(s) for s in item.xpath(".//span")]
            composite = next((s for s in spans if s and s not in {"–", "-"}), "")
        if "SPREAD" in label:
            match = re.match(r"([A-Z]{2,4})\s*([+-]?\d+(?:\.\d+)?)", composite)
            if match:
                team = canon_nba(match.group(1))
                spread = float(match.group(2))
            elif composite.upper().startswith("PK"):
                spread = 0.0
        elif "O/U" in label:
            match = re.search(r"(\d+(?:\.\d+)?)", composite)
            if match:
                total = float(match.group(1))
    return team, spread, total


def parse_lineups(page: str, fallback_date: str) -> list[dict]:
    doc = lxml_html.fromstring(page)
    slate_match = re.search(r"Starting lineups for ([A-Za-z]+ \d+, \d{4})", page)
    game_date = fallback_date
    if slate_match:
        try:
            game_date = dt.datetime.strptime(slate_match.group(1), "%B %d, %Y").date().isoformat()
        except ValueError:
            pass
    games = []
    for box in doc.xpath("//div[contains(concat(' ', normalize-space(@class), ' '), ' lineup ')"
                         " and contains(@class,'is-nba') and @data-lnum]"):
        visit_code, visit_players = parse_side(box, "is-visit")
        home_code, home_players = parse_side(box, "is-home")
        if not visit_code or not home_code:
            continue
        spread_team, spread, total = parse_spread(box)
        home_spread = None
        if spread is not None:
            home_spread = spread if (not spread_team or spread_team == home_code) else -spread
        games.append({
            "date": game_date,
            "home": home_code,
            "away": visit_code,
            "home_spread": home_spread,
            "total": total,
            "players": [(home_code, home_players), (visit_code, visit_players)],
        })
    return games


def save(con, games: list[dict]) -> tuple[int, int]:
    now = dt.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"
    injuries = 0
    spreads = 0
    for g in games:
        for team, players in g["players"]:
            con.execute("DELETE FROM injury WHERE league=? AND game_date=? AND team=?",
                        (LEAGUE, g["date"], team))
            rows = [(LEAGUE, g["date"], team, p["name"], name_key(p["name"]), p["status"] or None,
                     1 if p["starter"] else 0, now) for p in players if p["name"]]
            con.executemany(
                "INSERT OR REPLACE INTO injury(league, game_date, team, player_name, name_key, status, starter,"
                " fetched_at) VALUES (?,?,?,?,?,?,?,?)", rows)
            injuries += len(rows)
        if g["home_spread"] is not None:
            con.execute(
                "INSERT OR REPLACE INTO game_spread(league, game_date, home, away, source, fetched_at, home_spread,"
                " total) VALUES (?,?,?,?,?,?,?,?)",
                (LEAGUE, g["date"], g["home"], g["away"], "rotowire", now, g["home_spread"], g["total"]))
            spreads += 1
    con.commit()
    return injuries, spreads


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    args = ap.parse_args()
    con = connect(args.db)
    today = dt.date.today()
    found: list[dict] = []
    for i, url in enumerate(LINEUPS_URLS):
        day = today if i == 0 else today + dt.timedelta(days=1)
        try:
            page = _get(url)
        except (urllib.error.URLError, OSError) as exc:
            log(f"[RotoWire] {url}: {exc}")
            continue
        games = parse_lineups(page, day.isoformat())
        log(f"[RotoWire] {url}: {len(games)} games")
        found.extend(games)
    n_inj, n_sp = save(con, found)
    log(f"[RotoWire] stored {n_inj} injury rows, {n_sp} spreads")


if __name__ == "__main__":
    main()
