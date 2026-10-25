#!/usr/bin/env python3
"""
NBA player-prop pipeline.

Same model as the Euroleague runner. Reads the 'NBA Daily' tab, pulls
Basketball-Reference gamelogs (one or two season links) and joins pace from
the pbpstats team link in Exp Pace.

Does not read or write 'Daily Bets Inputs'. Euroleague keeps using
run_daily_bets.py.

Usage
-----
python run_nba_daily.py
python run_nba_daily.py --dry-run
python run_nba_daily.py --player "Thompson"
"""

from __future__ import annotations

import argparse
import sys
import traceback

from config import DEFAULT_BANKROLL, SERVICE_ACCOUNT_FILE, SHEET_ID
from nba_sources import load_nba_games
from props_model import evaluate_bet, public_post
from sheets_client import (
    ensure_input_headers,
    open_client,
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


def _nba_worksheet(spreadsheet):
    for ws in spreadsheet.worksheets():
        folded = ws.title.casefold()
        if "daily" in folded and "bets" not in folded and "input" not in folded:
            return ws
    titles = ", ".join(ws.title for ws in spreadsheet.worksheets())
    raise RuntimeError(f"No NBA Daily tab. Tabs: {titles}")


def process_pending(dry_run: bool = False, player_filter: str = "") -> int:
    if not SERVICE_ACCOUNT_FILE.exists():
        raise FileNotFoundError(
            f"Missing credentials file: {SERVICE_ACCOUNT_FILE}"
        )

    gc = open_client()
    spreadsheet = gc.open_by_key(SHEET_ID)
    inputs_ws = _nba_worksheet(spreadsheet)
    ensure_input_headers(inputs_ws)
    bets = pending_bets(inputs_ws)
    if player_filter:
        needle = player_filter.lower()
        bets = [b for b in bets if needle in b.player.lower()]

    print(f"Sheet {SHEET_ID}")
    print(f"Tab {inputs_ws.title}")
    print(
        f"Pending rows: {len(bets)}  |  bankroll reference: {DEFAULT_BANKROLL:.2f} EUR"
    )
    print("Euroleague tab is not used.")
    if not bets:
        print("Nothing to process (Model Prob already filled).")
        return 0

    errors = 0
    html_cache: dict[str, str] = {}
    pace_cache: dict = {}
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
                    "Player URL is blank, and this player name has no earlier link."
                )

            games, n_matched, notes = load_nba_games(
                bet.url,
                bet.pace_url,
                html_cache=html_cache,
                pace_cache=pace_cache,
            )
            print(f"  scraped {len(games)} games")
            for note in notes:
                print(f"  {note}")
            if bet.pace_url:
                print(f"  pace link matched {n_matched}/{len(games)} games")
                if n_matched == 0:
                    raise RuntimeError(
                        "Pace link matched 0 games. Use the pbpstats log of this player's team."
                    )
            result = evaluate_bet(games, bet)
            p_pct = result.model_prob * 100.0
            ev_pct = result.stake.ev * 100.0
            print(
                f"  P_filters={_fmt_p(result.p_filters)}  "
                f"P_sim={_fmt_p(result.p_similarity)}  "
                f"P_final={p_pct:.2f}%"
            )
            for factor in result.factors:
                hr = "n/a" if factor.hit_rate is None else f"{factor.hit_rate:.1%}"
                print(
                    f"    {factor.name:8s} N={factor.n:3d}  Z={factor.z:.3f}  "
                    f"w={factor.effective_weight:.1%}  hit={hr}"
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
    parser = argparse.ArgumentParser(description="NBA daily props pipeline")
    parser.add_argument("--dry-run", action="store_true", help="Scrape and model, do not write")
    parser.add_argument("--player", default="", help="Substring filter on player name")
    args = parser.parse_args()
    n_err = process_pending(dry_run=args.dry_run, player_filter=args.player)
    if n_err:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
