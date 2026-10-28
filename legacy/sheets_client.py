"""Google Sheets I/O for Daily Bets Inputs via a service account."""

from __future__ import annotations

from typing import Any, Optional

import gspread
from google.oauth2.service_account import Credentials

from config import (
    BANKROLL_TAB,
    DEFAULT_BANKROLL,
    FTA_HEADERS,
    HEADER_ALIASES,
    INPUT_HEADERS,
    INPUTS_TAB,
    PROTECTED_HEADERS,
    SERVICE_ACCOUNT_FILE,
    SHEET_ID,
    WRITE_HEADERS,
)
from props_model import BetInput, parse_bool, parse_estimate, parse_pace_cell


SCOPES = (
    "https://www.googleapis.com/auth/spreadsheets",
    "https://www.googleapis.com/auth/drive",
)


def open_client(credentials_path: Optional[str] = None) -> gspread.Client:
    path = credentials_path or str(SERVICE_ACCOUNT_FILE)
    creds = Credentials.from_service_account_file(path, scopes=SCOPES)
    return gspread.authorize(creds)


def open_worksheets(gc: gspread.Client):
    sh = gc.open_by_key(SHEET_ID)
    try:
        inputs = sh.worksheet(INPUTS_TAB)
    except gspread.WorksheetNotFound:
        inputs = sh.add_worksheet(title=INPUTS_TAB, rows=200, cols=20)
        inputs.update("A1:T1", [INPUT_HEADERS])
    try:
        bankroll = sh.worksheet(BANKROLL_TAB)
    except gspread.WorksheetNotFound:
        bankroll = sh.add_worksheet(title=BANKROLL_TAB, rows=10, cols=8)
        bankroll.update(
            "A1:E2",
            [
                [
                    "Initial Bankroll",
                    "Total Bets",
                    "Total Profit (€)",
                    "Current Bankroll",
                    "ROI (%)",
                ],
                [
                    DEFAULT_BANKROLL,
                    f"=COUNTA('{INPUTS_TAB}'!A2:A)",
                    f"=SUM('{INPUTS_TAB}'!S2:S)",
                    "=A2+C2",
                    "=(C2/A2)*100",
                ],
            ],
        )
    return sh, inputs, bankroll


def _header_map(ws: gspread.Worksheet) -> dict[str, int]:
    headers = ws.row_values(1)
    mapping = {}
    for idx, name in enumerate(headers, start=1):
        key = str(name).strip()
        if key:
            mapping[key] = idx
    missing = [
        h
        for h in INPUT_HEADERS
        if not any(alias in mapping for alias in HEADER_ALIASES.get(h, (h,)))
    ]
    if missing:
        start = len(headers) + 1
        end = start + len(missing) - 1
        if end > ws.col_count:
            ws.resize(cols=end)
        for offset, name in enumerate(missing):
            col = start + offset
            ws.update_cell(1, col, name)
            mapping[name] = col
    return mapping


def ensure_input_headers(ws: gspread.Worksheet) -> dict[str, int]:
    """Add any missing pipeline headers. Existing cells stay as they are."""
    return _header_map(ws)


def _cell(row: dict[str, Any], *names: str) -> Any:
    for name in names:
        if name in row and str(row[name]).strip() != "":
            return row[name]
    return ""


def _to_float(value: Any) -> Optional[float]:
    if value in (None, ""):
        return None
    text = str(value).strip().replace("%", "").replace(",", ".")
    if not text:
        return None
    try:
        return float(text)
    except ValueError as exc:
        raise ValueError(f"Invalid number '{value}'") from exc


def _player_key(name: object) -> str:
    return " ".join(str(name or "").strip().casefold().split())


def remembered_links(
    rows: list[tuple[int, dict[str, Any]]],
    player: str,
    sheet_row: int,
) -> tuple[str, str]:
    """Latest Hackastat URL and pace URL for this exact player name."""
    key = _player_key(player)
    url = ""
    pace_url = ""
    best_url_row = -1
    best_pace_row = -1
    if not key:
        return "", ""
    for row_n, rec in rows:
        if row_n == sheet_row or _player_key(_cell(rec, "Player Name")) != key:
            continue
        found = str(_cell(rec, "Hackastat URL")).strip()
        if found and row_n > best_url_row:
            url = found
            best_url_row = row_n
        _estimate, found_pace = parse_pace_cell(_cell(rec, "Exp Pace"))
        if found_pace and row_n > best_pace_row:
            pace_url = found_pace
            best_pace_row = row_n
    return url, pace_url


def records_with_rows(ws: gspread.Worksheet) -> list[tuple[int, dict[str, Any]]]:
    rows = ws.get_all_records()
    out = []
    for i, rec in enumerate(rows, start=2):
        out.append((i, rec))
    return out


def pending_bets(ws: gspread.Worksheet) -> list[BetInput]:
    """Rows where Model Prob (%) is blank."""
    bets: list[BetInput] = []
    rows = records_with_rows(ws)
    for sheet_row, rec in rows:
        model = str(_cell(rec, "Model Prob (%)")).strip()
        if model:
            continue
        status = str(_cell(rec, "Status")).strip().upper()
        if status.startswith("ERROR"):
            continue
        player = str(_cell(rec, "Player Name")).strip()
        url = str(_cell(rec, "Hackastat URL")).strip()
        if not player and not url:
            continue
        exp_pace, pace_url = parse_pace_cell(_cell(rec, "Exp Pace"))
        saved_url, saved_pace = remembered_links(rows, player, sheet_row)
        url_from_memory = False
        pace_from_memory = False
        if not url and saved_url:
            url = saved_url
            url_from_memory = True
        if exp_pace is not None and not pace_url and saved_pace:
            pace_url = saved_pace
            pace_from_memory = True
        bets.append(
            BetInput(
                date=str(_cell(rec, "Date")).strip(),
                player=player,
                url=url,
                stat_type=str(_cell(rec, "Stat Type")).strip(),
                line=_to_float(_cell(rec, "Target Line")),
                odds=_to_float(_cell(rec, "Odds")),
                exp_mins=parse_estimate(_cell(rec, "Exp Mins")),
                exp_fga=parse_estimate(_cell(rec, "Exp FGA")),
                exp_fta=parse_estimate(_cell(rec, *FTA_HEADERS)),
                exp_usg=parse_estimate(_cell(rec, "Exp Usage%")),
                exp_pace=exp_pace,
                exp_3pa=parse_estimate(_cell(rec, "x3PA", "X3PA", "Exp 3PA")),
                is_home=parse_bool(_cell(rec, "Is Home?")),
                h2h_raw=str(_cell(rec, "H2H Opponent")).strip(),
                sheet_row=sheet_row,
                pace_url=pace_url,
                url_from_memory=url_from_memory,
                pace_from_memory=pace_from_memory,
            )
        )
    return bets


def write_blank_player_url(ws: gspread.Worksheet, sheet_row: int, url: str) -> None:
    """Fill Hackastat URL only when the cell is empty."""
    if not url:
        return
    headers = _header_map(ws)
    col = headers.get("Hackastat URL")
    if not col:
        return
    current = ws.cell(sheet_row, col).value
    if current and str(current).strip():
        return
    ws.update_cell(sheet_row, col, url)


def write_result(
    ws: gspread.Worksheet,
    sheet_row: int,
    model_prob_pct: Optional[float],
    ev_pct: Optional[float],
    stake: Optional[float],
    status: str,
    post: Optional[str] = None,
) -> None:
    headers = _header_map(ws)
    for name in WRITE_HEADERS:
        if name in PROTECTED_HEADERS:
            continue
        if name not in headers:
            raise KeyError(f"Missing output column '{name}'")
    payload = {
        "Model Prob (%)": "" if model_prob_pct is None else round(model_prob_pct, 2),
        "EV (%)": "" if ev_pct is None else round(ev_pct, 2),
        "Stake (€)": "" if stake is None else round(stake, 2),
        "Status": status,
    }
    if post is not None:
        payload["Post"] = post
    cells = []
    for name, value in payload.items():
        cells.append(gspread.Cell(sheet_row, headers[name], value))
    ws.update_cells(cells, value_input_option="USER_ENTERED")


def current_bankroll(ws: gspread.Worksheet) -> float:
    try:
        raw = ws.acell("D2").value
        val = _to_float(raw)
        return val if val and val > 0 else DEFAULT_BANKROLL
    except Exception:
        return DEFAULT_BANKROLL
