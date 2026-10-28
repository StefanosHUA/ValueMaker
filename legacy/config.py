"""Shared configuration for the Euroleague daily props pipeline."""

from __future__ import annotations

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent

# Existing Fermabets spreadsheet (override with FERMABETS_SHEET_ID).
SHEET_ID = os.environ.get(
    "FERMABETS_SHEET_ID",
    "1bwA3l_aKJAR5D-1V_idnBIfc1BruZMceqDK92F4_yHo",
)
INPUTS_TAB = os.environ.get("FERMABETS_INPUTS_TAB", "Daily Bets Inputs")
BANKROLL_TAB = os.environ.get("FERMABETS_BANKROLL_TAB", "Bankroll Log")

def _resolve_service_account() -> Path:
    env = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")
    if env:
        return Path(env)
    primary = ROOT / "service_account.json"
    if primary.exists():
        return primary
    # Windows sometimes saves the download as service_account.json.json
    doubled = ROOT / "service_account.json.json"
    if doubled.exists():
        return doubled
    return primary


SERVICE_ACCOUNT_FILE = _resolve_service_account()

# Persistent Chrome profile so Cloudflare cookies survive between runs.
CHROME_PROFILE_DIR = Path(
    os.environ.get("HACKASTAT_CHROME_PROFILE", str(ROOT / ".hackastat_chrome"))
)
CDP_DEFAULT = os.environ.get("HACKASTAT_CDP", "").strip()

BUHLMANN_K = 5.0
# Smaller K so 2 H2H games still matter: Z = 2/(2+2) = 50%.
H2H_K = 2.0
DEFAULT_BANKROLL = 100.0
KELLY_FRACTION = 0.25
MAX_STAKE_PCT = 0.05  # 5€ on a 100€ bankroll

# Blend between filter-based credibility (A) and per-game similarity (B).
BLEND_A = 0.55
BLEND_B = 0.45

# Base weights before missing-factor drop and re-normalization.
# Points: usage stands in for possessions. FTA and 3PA are optional extras,
# 0.10 each. A blank cell drops that one and the rest stay in proportion.
# With neither filled the weights return to 30/25/15/10/10/10. One filled
# extra lands near 9%. Both filled land near 8% each.
# Pace stays separate so a tight pace range does not wipe usage.
# H2H share is capped at this listed weight.
BASE_WEIGHTS = {
    "mins": 0.30,
    "fga": 0.25,
    "fta": 0.10,
    "3pa": 0.10,
    "usg": 0.15,
    "pace": 0.10,
    "home": 0.10,
    "h2h": 0.10,
}

# Rebound scheme: minutes, pace, home/away, H2H only (no FGA/USG/Poss).
REB_BASE_WEIGHTS = {
    "mins": 0.40,
    "pace": 0.25,
    "home": 0.20,
    "h2h": 0.15,
}

# Threes: same inputs as scoring, plus a 3PA floor.
# 25 points move off mins/FGA/volume/home onto 3PA, because attempts
# are the closest input to made threes. H2H stays at 10%.
THREE_BASE_WEIGHTS = {
    "3pa": 0.25,
    "mins": 0.20,
    "fga": 0.15,
    "volume": 0.15,  # Usage / Pace group. Possessions are not used.
    "home": 0.15,
    "h2h": 0.10,
}

# Assists: FGA is the pass-vs-shoot filter. Possessions are omitted because
# usage already measures how much of the offense runs through him.
AST_BASE_WEIGHTS = {
    "mins": 0.30,
    "pace": 0.20,
    "fga": 0.15,
    "usg": 0.15,
    "home": 0.10,
    "h2h": 0.10,
}

STAT_BASE_WEIGHTS = {
    "trb": REB_BASE_WEIGHTS,
    "orb": REB_BASE_WEIGHTS,
    "drb": REB_BASE_WEIGHTS,
    "3pm": THREE_BASE_WEIGHTS,
    "3pa": THREE_BASE_WEIGHTS,
    "ast": AST_BASE_WEIGHTS,
}

SIMILARITY_FEATURES = {
    "default": ("min", "fga", "fta", "3pa", "usg", "pace"),
    "reb": ("min", "pace"),
    "three": ("min", "fga", "3pa", "usg", "pace"),
    "ast": ("min", "fga", "usg", "pace"),
}

VOLUME_FACTORS = ("usg", "pace")

INPUT_HEADERS = [
    "Date",
    "Player Name",
    "Hackastat URL",
    "Stat Type",
    "Target Line",
    "Odds",
    "Exp Mins",
    "Exp FGA",
    "Exp Possessions",
    "Exp Usage%",
    "Exp Pace",
    "Is Home?",
    "H2H Opponent",
    "x3PA",
    "EV (%)",
    "Stake (€)",
    "Status",
    "Result",
    "Profit/Loss (€)",
    "Model Prob (%)",
    "Post",
]

WRITE_HEADERS = ("Model Prob (%)", "EV (%)", "Stake (€)", "Status", "Post")
PROTECTED_HEADERS = (
    "Date",
    "Player Name",
    "Hackastat URL",
    "Stat Type",
    "Target Line",
    "Odds",
    "Exp Mins",
    "Exp FGA",
    "Exp Possessions",
    "Exp Usage%",
    "Exp Pace",
    "x3PA",
    "Is Home?",
    "H2H Opponent",
    "Result",
    "Profit/Loss (€)",
)

# Column I is free-throw attempts. The live header can stay "Exp Possessions".
FTA_HEADERS = ("Exp FTA", "FTA", "xFTA", "Exp Possessions")

HEADER_ALIASES = {
    "Exp Possessions": FTA_HEADERS,
}

STAT_ALIASES = {
    "pts": ("pts", "points", "p", "point"),
    "trb": ("trb", "reb", "rebs", "rebound", "rebounds", "r"),
    "orb": ("orb", "oreb"),
    "drb": ("drb", "dreb"),
    "ast": ("ast", "assist", "assists", "a"),
    "stl": ("stl", "st", "steal", "steals"),
    "blk": ("blk", "block", "blocks"),
    "tov": ("tov", "to", "turnover", "turnovers"),
    "fgm": ("fgm",),
    "fga": ("fga",),
    "3pm": (
        "3pm", "tpm", "threes", "three", "3pt", "3pts", "3p",
        "triples", "3pointers", "threepointers", "threepoints", "threepoint",
    ),
    "3pa": ("3pa", "tpa", "3patt", "threeattempts"),
    "pra": ("pra", "pts+reb+ast"),
    "pr": ("pr", "pts+reb"),
    "pa": ("pa", "pts+ast"),
    "ra": ("ra", "reb+ast"),
}
