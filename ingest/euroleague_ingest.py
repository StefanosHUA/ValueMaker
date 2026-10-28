"""Populate the shared SQLite DB from euroleague_api (incremental: only new games are fetched).

Usage: python euroleague_ingest.py --db ../data/fermabets.db [--seasons 2024,2025] [--competition E]
"""
from __future__ import annotations

import argparse
import datetime as dt

from euroleague_api.boxscore_data import BoxScoreData

from common import connect, log, name_key, num, pretty_name

COMPETITIONS = {"E": "EL", "U": "EC"}


def current_season(today: dt.date) -> int:
    return today.year if today.month >= 9 else today.year - 1


def parse_minutes(value) -> float:
    text = str(value or "").strip()
    if not text or text.upper() == "DNP":
        return 0.0
    if ":" in text:
        m, s = text.split(":")[:2]
        return float(m) + float(s) / 60.0
    try:
        return float(text)
    except ValueError:
        return 0.0


def parse_date(text: str) -> str:
    return dt.datetime.strptime(text, "%b %d, %Y").date().isoformat()


def ingest_season(con, box: BoxScoreData, league: str, season: int) -> None:
    games = box.get_gamecodes_season(season)
    if games.empty:
        log(f"[{league}] {season}: no games")
        return
    con.execute("DELETE FROM schedule WHERE league=? AND game_date>=?", (league, dt.date.today().isoformat()))
    for g in games.itertuples():
        game_date = parse_date(g.date)
        if not g.played:
            con.execute("INSERT OR REPLACE INTO schedule VALUES (?,?,?,?)", (league, game_date, g.homecode, g.awaycode))
    con.commit()

    done = {r[0] for r in con.execute("SELECT DISTINCT game_id FROM player_game WHERE league=? AND season=?",
                                      (league, str(season)))}
    todo = [g for g in games.itertuples() if g.played and f"{league}:{g.gamecode}" not in done]
    log(f"[{league}] {season}: {len(todo)} new games to fetch")
    for i, g in enumerate(todo, 1):
        game_id = f"{league}:{g.gamecode}"
        game_date = parse_date(g.date)
        try:
            df = box.get_players_boxscore_stats(season, int(g.gameCode))
        except Exception as exc:  # noqa: BLE001
            log(f"  skip {game_id}: {exc}")
            continue
        save_game(con, league, season, game_id, game_date, g, df)
        if i % 10 == 0:
            con.commit()
            log(f"[{league}] {season}: {i}/{len(todo)}")
    con.commit()


def save_game(con, league, season, game_id, game_date, g, df) -> None:
    pl_rows, pg_rows = [], []
    totals: dict[str, dict[str, float]] = {}
    for r in df.itertuples(index=False):
        minutes = parse_minutes(r.Minutes)
        team = str(r.Team).strip()
        opp = g.awaycode if team == g.homecode else g.homecode
        if minutes <= 0:
            continue
        pid = f"{league}:{str(r.Player_ID).strip()}"
        fga = num(r.FieldGoalsAttempted2) + num(r.FieldGoalsAttempted3)
        pl_rows.append((pid, league, pretty_name(r.Player), name_key(r.Player)))
        pg_rows.append((game_id, pid, league, str(season), game_date, team, opp, 1 if team == g.homecode else 0,
                        minutes, num(r.Points), num(r.TotalRebounds), num(r.Assistances),
                        num(r.FieldGoalsMade3), num(r.FieldGoalsAttempted3), fga,
                        num(r.FreeThrowsAttempted), num(r.Steals), num(r.BlocksFavour), num(r.Turnovers)))
        t = totals.setdefault(team, {"pts": 0, "fga": 0, "fta": 0, "tov": 0, "oreb": 0, "pf": 0})
        t["pts"] += num(r.Points)
        t["fga"] += fga
        t["fta"] += num(r.FreeThrowsAttempted)
        t["tov"] += num(r.Turnovers)
        t["oreb"] += num(r.OffensiveRebounds)
        t["pf"] += num(r.FoulsCommited)
    con.executemany("INSERT OR REPLACE INTO player VALUES (?,?,?,?)", pl_rows)
    con.executemany("INSERT OR REPLACE INTO player_game VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", pg_rows)
    for team, t in totals.items():
        poss = t["fga"] + 0.44 * t["fta"] - t["oreb"] + t["tov"]
        opp = g.awaycode if team == g.homecode else g.homecode
        con.execute("INSERT OR REPLACE INTO team_game VALUES (?,?,?,?,?,?,?,?,?,?)",
                    (game_id, league, team, opp, game_date, 1 if team == g.homecode else 0,
                     t["pts"], poss, t["fta"], t["pf"]))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--seasons", default="")
    ap.add_argument("--competition", default="E", choices=list(COMPETITIONS))
    args = ap.parse_args()
    con = connect(args.db)
    league = COMPETITIONS[args.competition]
    box = BoxScoreData(competition=args.competition)
    if args.seasons:
        seasons = [int(s) for s in args.seasons.split(",") if s.strip()]
    else:
        start = current_season(dt.date.today())
        seasons = [start - 1, start]
    for season in seasons:
        ingest_season(con, box, league, season)
    log(f"[{league}] done")


if __name__ == "__main__":
    main()
