"""Populate the shared SQLite DB from nba_api.

Usage: python nba_ingest.py --db ../data/fermabets.db [--seasons 2024-25,2025-26] [--referee-limit 200]
"""
from __future__ import annotations

import argparse
import datetime as dt
import time
import warnings

import pandas as pd
from nba_api.stats.endpoints import BoxScoreSummaryV2, BoxScoreSummaryV3, LeagueGameLog, ScoreboardV2
from nba_api.stats.static import teams as nba_teams

from common import connect, log, name_key, num

warnings.filterwarnings("ignore")

LEAGUE = "NBA"


def current_season(today: dt.date) -> int:
    return today.year if today.month >= 10 else today.year - 1


def season_label(start: int) -> str:
    return f"{start}-{str(start + 1)[-2:]}"


def retry(fn, tries=4):
    for attempt in range(tries):
        try:
            return fn()
        except Exception as exc:  # noqa: BLE001
            if attempt == tries - 1:
                raise
            log(f"  retry after error: {exc}")
            time.sleep(3 * (attempt + 1))


def parse_matchup(matchup: str) -> tuple[str, int]:
    """'LAL vs. BOS' -> (BOS, home); 'LAL @ BOS' -> (BOS, away)."""
    if " @ " in matchup:
        return matchup.split(" @ ")[1].strip(), 0
    return matchup.split(" vs. ")[1].strip(), 1


def parse_minutes(value) -> float:
    if value is None or value != value:
        return 0.0
    text = str(value)
    if ":" in text:
        m, s = text.split(":")[:2]
        return float(m) + float(s) / 60.0
    try:
        return float(text)
    except ValueError:
        return 0.0


def ingest_season(con, label: str) -> None:
    for season_type in ("Regular Season", "Playoffs"):
        log(f"[NBA] {label} {season_type}: player logs")
        players = retry(lambda: LeagueGameLog(
            season=label, season_type_all_star=season_type,
            player_or_team_abbreviation="P").get_data_frames()[0])
        log(f"[NBA] {label} {season_type}: team logs")
        teams = retry(lambda: LeagueGameLog(
            season=label, season_type_all_star=season_type,
            player_or_team_abbreviation="T").get_data_frames()[0])
        if players.empty:
            log(f"[NBA] {label} {season_type}: no games yet")
            continue
        save_players(con, label, players)
        save_teams(con, teams)
        con.commit()
        log(f"[NBA] {label} {season_type}: {len(players)} player rows, {len(teams)} team rows")


def save_players(con, label: str, df: pd.DataFrame) -> None:
    pl_rows, pg_rows = [], []
    for r in df.itertuples(index=False):
        opp, is_home = parse_matchup(r.MATCHUP)
        minutes = parse_minutes(r.MIN)
        if minutes <= 0:
            continue
        pid = f"NBA:{r.PLAYER_ID}"
        pl_rows.append((pid, LEAGUE, r.PLAYER_NAME, name_key(r.PLAYER_NAME)))
        pg_rows.append((f"NBA:{r.GAME_ID}", pid, LEAGUE, label, r.GAME_DATE, r.TEAM_ABBREVIATION, opp, is_home,
                        minutes, num(r.PTS), num(r.REB), num(r.AST), num(r.FG3M), num(r.FG3A),
                        num(r.FGA), num(r.FTA), num(r.STL), num(r.BLK), num(r.TOV)))
    con.executemany("INSERT OR REPLACE INTO player VALUES (?,?,?,?)", pl_rows)
    con.executemany("INSERT OR REPLACE INTO player_game VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", pg_rows)


def save_teams(con, df: pd.DataFrame) -> None:
    rows = []
    for r in df.itertuples(index=False):
        opp, is_home = parse_matchup(r.MATCHUP)
        poss = num(r.FGA) + 0.44 * num(r.FTA) - num(r.OREB) + num(r.TOV)
        rows.append((f"NBA:{r.GAME_ID}", LEAGUE, r.TEAM_ABBREVIATION, opp, r.GAME_DATE, is_home,
                     num(r.PTS), poss, num(r.FTA), num(r.PF), parse_minutes(r.MIN)))
    con.executemany(
        "INSERT OR REPLACE INTO team_game(game_id, league, team, opp, game_date, is_home, pts, poss, fta, pf, minutes)"
        " VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)


def fetch_officials(raw_game_id: str) -> list[str]:
    """V3 has recent games; V2 is the fallback for older ones."""
    try:
        df = retry(lambda: BoxScoreSummaryV3(game_id=raw_game_id).officials.get_data_frame(), tries=2)
        names = [str(n).strip() for n in df["name"] if str(n).strip()]
        if names:
            return names
    except Exception:  # noqa: BLE001
        pass
    df = retry(lambda: BoxScoreSummaryV2(game_id=raw_game_id).officials.get_data_frame(), tries=2)
    return [f"{o.FIRST_NAME} {o.LAST_NAME}".strip() for o in df.itertuples()]


def ingest_referees(con, limit: int) -> None:
    rows = con.execute(
        """SELECT DISTINCT game_id FROM team_game
           WHERE league='NBA' AND game_id NOT IN (SELECT game_id FROM game_official)
           ORDER BY game_date DESC LIMIT ?""", (limit,)).fetchall()
    log(f"[NBA] referees: {len(rows)} games to fetch")
    for i, (game_id,) in enumerate(rows, 1):
        raw = game_id.split(":", 1)[1]
        try:
            names = fetch_officials(raw)
        except Exception as exc:  # noqa: BLE001
            log(f"  skip {raw}: {exc}")
            continue
        con.executemany("INSERT OR IGNORE INTO game_official VALUES (?,?)", [(game_id, n) for n in names])
        if i % 25 == 0:
            con.commit()
            log(f"[NBA] referees {i}/{len(rows)}")
        time.sleep(0.6)
    con.commit()


def ingest_schedule(con, days_ahead: int = 3) -> None:
    """Upcoming games by US date. Starts yesterday because European users are a day ahead of the NBA calendar."""
    from nba_api.stats.endpoints import ScoreboardV3
    today = dt.date.today()
    for offset in range(-1, days_ahead + 1):
        day = today + dt.timedelta(days=offset)
        try:
            games = retry(lambda: ScoreboardV3(game_date=day.isoformat()).get_dict()["scoreboard"]["games"])
        except Exception as exc:  # noqa: BLE001
            log(f"[NBA] schedule {day}: {exc}")
            continue
        rows = [(LEAGUE, day.isoformat(), g["homeTeam"]["teamTricode"], g["awayTeam"]["teamTricode"])
                for g in games]
        con.execute("DELETE FROM schedule WHERE league='NBA' AND game_date=?", (day.isoformat(),))
        con.executemany("INSERT OR REPLACE INTO schedule VALUES (?,?,?,?)", rows)
        con.commit()
        log(f"[NBA] schedule {day}: {len(rows)} games")
        time.sleep(0.6)

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--seasons", default="")
    ap.add_argument("--referee-limit", type=int, default=200)
    args = ap.parse_args()
    con = connect(args.db)
    if args.seasons:
        labels = [s.strip() for s in args.seasons.split(",") if s.strip()]
    else:
        start = current_season(dt.date.today())
        labels = [season_label(start - 2), season_label(start - 1), season_label(start)]
    for label in labels:
        ingest_season(con, label)
    ingest_schedule(con)
    if args.referee_limit > 0:
        ingest_referees(con, args.referee_limit)
    log("[NBA] done")


if __name__ == "__main__":
    main()
