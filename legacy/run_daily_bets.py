#!/usr/bin/env python3
"""
Euroleague player-prop pipeline.

Reads pending rows from Google Sheet tab 'Daily Bets Inputs', scrapes
Hackastat.eu game logs with Playwright, scores each bet with a dual-layer
model (Bühlmann filter hit-rates + per-game similarity), sizes stakes with
Quarter-Kelly, and writes Model Prob / EV / Stake / Status.

Never overwrites Result or Profit/Loss.

Setup
-----
1. pip install -r requirements.txt
2. python -m playwright install chromium
3. Google Cloud → create a service account, download JSON as
   service_account.json in this folder.
4. Share the spreadsheet with the service account client_email as Editor.
5. Create tabs 'Daily Bets Inputs' and 'Bankroll Log' (or let the script
   create them on first run).

Usage
-----
python run_daily_bets.py
python run_daily_bets.py --dry-run
python run_daily_bets.py --headless
python run_daily_bets.py --player "Feliz"
"""

from __future__ import annotations

import argparse
import sys
import traceback

from config import CDP_DEFAULT, DEFAULT_BANKROLL, SERVICE_ACCOUNT_FILE, SHEET_ID
from hackastat_scraper import HackastatSession, attach_pace
from props_model import evaluate_bet, public_post
from sheets_client import (
    current_bankroll,
    open_client,
    open_worksheets,
    pending_bets,
    write_blank_player_url,
    write_result,
)


def _configure_stdout() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def process_pending(
    dry_run: bool = False,
    headed: bool = True,
    player_filter: str = "",
    cdp_url: str = "",
) -> int:
    if not SERVICE_ACCOUNT_FILE.exists():
        raise FileNotFoundError(
            f"Missing credentials file: {SERVICE_ACCOUNT_FILE}\n"
            "Download a Google service-account JSON and save it as "
            "service_account.json, then share the Sheet with client_email."
        )

    gc = open_client()
    _sh, inputs_ws, bankroll_ws = open_worksheets(gc)
    bankroll = current_bankroll(bankroll_ws) or DEFAULT_BANKROLL
    bets = pending_bets(inputs_ws)
    if player_filter:
        needle = player_filter.lower()
        bets = [b for b in bets if needle in b.player.lower()]

    print(f"Sheet {SHEET_ID}")
    print(f"Pending rows: {len(bets)}  |  bankroll reference: {bankroll:.2f} EUR")
    if not bets:
        print("Nothing to process (Model Prob already filled).")
        return 0

    errors = 0
    with HackastatSession(headed=headed, cdp_url=cdp_url or CDP_DEFAULT) as session:
        for bet in bets:
            label = f"row {bet.sheet_row} | {bet.player} | {bet.stat_type} {bet.line} @ {bet.odds}"
            print(f"\n--- {label}")
            try:
                if bet.line is None or bet.odds is None or bet.odds <= 1:
                    raise ValueError("Target Line and Odds > 1 are required.")
                if bet.url_from_memory:
                    print("  player link remembered from an earlier row")
                    if not dry_run:
                        write_blank_player_url(inputs_ws, bet.sheet_row, bet.url)
                if bet.pace_from_memory:
                    print("  pace link remembered from an earlier row")
                if not bet.url:
                    raise ValueError(
                        "Hackastat URL is blank, and this player name has no earlier link."
                    )

                games = session.scrape(bet.url)
                print(f"  scraped {len(games)} games")
                if bet.pace_url:
                    pace_log = session.scrape(bet.pace_url, player_table=False)
                    games, n_matched = attach_pace(games, pace_log)
                    print(f"  pace link matched {n_matched}/{len(games)} games")
                    if n_matched == 0:
                        raise RuntimeError(
                            "Pace link matched 0 games. The link needs a game log with dates and pace."
                        )
                result = evaluate_bet(games, bet)
                p_pct = result.model_prob * 100.0
                ev_pct = result.stake.ev * 100.0
                print(
                    f"  P_filters={_fmt_p(result.p_filters)}  "
                    f"P_sim={_fmt_p(result.p_similarity)}  "
                    f"P_final={p_pct:.2f}%"
                )
                for f in result.factors:
                    hr = "n/a" if f.hit_rate is None else f"{f.hit_rate:.1%}"
                    print(
                        f"    {f.name:8s} N={f.n:3d}  Z={f.z:.3f}  "
                        f"w={f.effective_weight:.1%}  hit={hr}"
                    )
                print(
                    f"  EV={ev_pct:+.2f}%  stake={result.stake.stake_euros:.2f}€  "
                    f"{result.stake.status}"
                    + (" [CAP]" if result.stake.capped else "")
                )
                if result.notes:
                    for note in result.notes:
                        print(f"  note: {note}")
                post = None
                if result.stake.status == "BET" and abs(result.stake.stake_euros - 5.0) < 0.001:
                    post = public_post(bet, result)
                    print("  post:")
                    print(post)

                if dry_run:
                    print("  dry-run: sheet not updated")
                    continue

                write_result(
                    inputs_ws,
                    bet.sheet_row,
                    model_prob_pct=p_pct,
                    ev_pct=ev_pct,
                    stake=result.stake.stake_euros,
                    status=result.stake.status,
                    post=post,
                )
                wrote = "Model Prob (T), EV, Stake, Status"
                if post is not None:
                    wrote += ", Post"
                print(f"  wrote {wrote} (Result untouched)")
            except Exception as exc:  # noqa: BLE001
                errors += 1
                print(f"  ERROR: {exc}")
                traceback.print_exc()
                if not dry_run:
                    try:
                        write_result(
                            inputs_ws,
                            bet.sheet_row,
                            model_prob_pct=None,
                            ev_pct=None,
                            stake=0.0,
                            status=f"ERROR: {exc}"[:80],
                        )
                    except Exception as write_exc:  # noqa: BLE001
                        print(f"  also failed to write error status: {write_exc}")
    return errors


def _fmt_p(p: float | None) -> str:
    return "n/a" if p is None else f"{p:.1%}"


def main() -> None:
    _configure_stdout()
    parser = argparse.ArgumentParser(description="Euroleague daily props pipeline")
    parser.add_argument("--dry-run", action="store_true", help="Scrape and model, do not write")
    parser.add_argument(
        "--headed",
        action="store_true",
        default=True,
        help="Show the browser (default; needed for Cloudflare).",
    )
    parser.add_argument(
        "--headless",
        action="store_true",
        help="Hide the browser (usually blocked by Cloudflare).",
    )
    parser.add_argument(
        "--cdp",
        default="",
        help="Connect to real Chrome, e.g. http://127.0.0.1:9222",
    )
    parser.add_argument("--player", default="", help="Substring filter on player name")
    args = parser.parse_args()
    n_err = process_pending(
        dry_run=args.dry_run,
        headed=not args.headless,
        player_filter=args.player,
        cdp_url=args.cdp,
    )
    if n_err:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
