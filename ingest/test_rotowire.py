"""Parse the RotoWire sample HTML. Run: python test_rotowire.py"""
from __future__ import annotations

from pathlib import Path

from rotowire_ingest import parse_lineups

SAMPLE = Path(__file__).resolve().parents[1] / "src" / "test" / "resources" / "rotowire_sample.html"


def main() -> None:
    games = parse_lineups(SAMPLE.read_text(encoding="utf-8"), "2026-10-28")
    assert len(games) == 1, games
    g = games[0]
    assert g["date"] == "2026-10-28"
    assert g["home"] == "CLE"
    assert g["away"] == "CHA"
    assert g["home_spread"] == -9.5, g["home_spread"]
    assert g["total"] == 235.5
    home_code, home_players = g["players"][0]
    away_code, away_players = g["players"][1]
    assert home_code == "CLE" and away_code == "CHA"
    grant = next(p for p in away_players if p["name"] == "Grant Williams")
    assert grant["status"] == "Out" and grant["starter"] is False
    lamelo = next(p for p in away_players if p["name"] == "LaMelo Ball")
    assert lamelo["starter"] is True
    print("rotowire parse ok")


if __name__ == "__main__":
    main()
