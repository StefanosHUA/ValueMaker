"""Print the Python props_model probability for the Java parity snapshot."""
from __future__ import annotations

import sys
from pathlib import Path

import pandas as pd

root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / "legacy"))
desktop = Path.home() / "OneDrive" / "Desktop" / "Fermabets"
if desktop.exists():
    sys.path.insert(0, str(desktop))

from props_model import BetInput, Estimate, evaluate_bet  # noqa: E402

opp = ["NYK", "BOS", "MIA", "BOS", "CHI", "NYK", "BOS", "ATL", "BOS", "ORL", "NYK", "BOS"]
home = [True, True, False, True, True, False, True, True, False, True, True, True]
rows = []
for i in range(12):
    minutes = 24 + (i % 8)
    fga = 8 + (i % 7)
    fta = i % 5
    pa3 = 2 + (i % 5)
    usg = 18 + (i % 9)
    pace = 96 + (i % 8)
    pts = 6 + 0.35 * minutes + 0.4 * fga + (i % 3)
    rows.append({
        "pts": pts, "min": minutes, "fga": fga, "fta": fta, "3pa": pa3,
        "usg": usg, "pace": pace, "is_home": home[i], "opp": opp[i],
    })
games = pd.DataFrame(rows)
bet = BetInput(
    date="2026-10-28", player="Test", url="", stat_type="pts", line=20.5, odds=1.80,
    exp_mins=Estimate(26, 32), exp_fga=Estimate(10, 16), exp_fta=Estimate(1, 5),
    exp_usg=Estimate(20, 28), exp_pace=Estimate(96, 104), exp_3pa=Estimate(3, 8),
    is_home=True, h2h_raw="BOS", sheet_row=0,
)
r = evaluate_bet(games, bet)
print(f"prob={r.model_prob:.8f}")
print(f"filters={r.p_filters:.8f}")
print(f"similarity={r.p_similarity:.8f}")
