"""Scrape and clean Hackastat.eu game-by-game logs with Playwright."""

from __future__ import annotations

import re
from datetime import datetime
from typing import Optional

import pandas as pd
from playwright.sync_api import TimeoutError as PlaywrightTimeout
from playwright.sync_api import sync_playwright

HEADER_ALIASES: dict[str, tuple[str, ...]] = {
    "date": ("date", "giorno", "day", "match date", "matchday", "game date"),
    "opp": ("opp", "opponent", "vs", "against", "avversario", "opp.", "opposing"),
    "loc": ("loc", "location", "ha", "h/a", "home/away", "venue", "where"),
    "min": ("min", "mins", "minutes", "mp", "min."),
    "fga": ("fga",),
    "fta": ("fta", "ft a"),
    "poss": ("poss", "possessions", "po", "poss."),
    "usg": ("usg%", "usg", "usage", "usage%", "usg %"),
    "pace": ("pace", "game_pace", "game pace", "tm pace", "team pace"),
    "pts": ("pts", "points"),
    "trb": ("trb", "reb", "rebs", "rebounds"),
    "orb": ("orb", "oreb"),
    "drb": ("drb", "dreb"),
    "ast": ("ast", "assists"),
    "stl": ("stl", "st", "steals"),
    "blk": ("blk", "blocks"),
    "tov": ("tov", "to", "turnovers"),
    "fgm": ("fgm",),
    "3pm": ("3pm", "tpm"),
    "3pa": ("3pa", "tpa"),
}


def minutes_to_float(value: object) -> Optional[float]:
    """Convert '22:30' or '22.5' or 22 to decimal minutes."""
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return None
    text = str(value).strip()
    if not text or text in {"-", "—", "NA", "N/A"}:
        return None
    if re.fullmatch(r"\d{1,3}:\d{2}(?:\.\d+)?", text):
        mins, secs = text.split(":", 1)
        return float(mins) + float(secs) / 60.0
    try:
        return float(text.replace(",", ".").replace("%", ""))
    except ValueError:
        return None


def to_float(value: object) -> Optional[float]:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return None
    text = str(value).strip().replace("%", "").replace(",", ".")
    if not text or text in {"-", "—", "NA", "N/A"}:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def _norm_header(name: object) -> str:
    return re.sub(r"\s+", " ", str(name or "").strip().lower())


def _map_header(raw: str) -> Optional[str]:
    key = _norm_header(raw)
    for canonical, aliases in HEADER_ALIASES.items():
        if key == canonical or key in aliases:
            return canonical
    return None


def parse_home_flag(loc: object, opp: object = "") -> Optional[bool]:
    """Infer home (True) / away (False) from location or opponent text."""
    loc_txt = str(loc or "").strip().lower()
    opp_txt = str(opp or "").strip().lower()
    blob = f"{loc_txt} {opp_txt}"
    if re.search(r"\b(home|h|casa)\b", loc_txt) or loc_txt in {"h", "home"}:
        return True
    if re.search(r"\b(away|a|trasferta|road)\b", loc_txt) or loc_txt in {"a", "away"}:
        return False
    if opp_txt.startswith("vs") or " vs " in f" {opp_txt} ":
        return True
    if opp_txt.startswith("@") or " @ " in f" {opp_txt} ":
        return False
    if "home" in blob:
        return True
    if "away" in blob:
        return False
    return None


def clean_game_log(raw: pd.DataFrame) -> pd.DataFrame:
    """Normalize scraped GBG columns to a modeling-ready frame."""
    if raw is None or raw.empty:
        return pd.DataFrame()

    rename: dict[str, str] = {}
    for col in raw.columns:
        mapped = _map_header(col)
        if mapped:
            rename[col] = mapped
    df = raw.rename(columns=rename).copy()

    if "min" in df.columns:
        df["min"] = df["min"].map(minutes_to_float)
    for col in ("fga", "fta", "poss", "usg", "pace", "pts", "trb", "orb", "drb", "ast", "stl", "blk", "tov", "fgm", "3pm", "3pa"):
        if col in df.columns:
            df[col] = df[col].map(to_float)

    if "trb" not in df.columns and {"orb", "drb"}.issubset(df.columns):
        df["trb"] = df["orb"].fillna(0) + df["drb"].fillna(0)

    combo_map = {
        "pra": ("pts", "trb", "ast"),
        "pr": ("pts", "trb"),
        "pa": ("pts", "ast"),
        "ra": ("trb", "ast"),
    }
    for name, parts in combo_map.items():
        if all(p in df.columns for p in parts):
            df[name] = sum(df[p].fillna(0) for p in parts)

    n = len(df)
    loc_vals = df["loc"].tolist() if "loc" in df.columns else [""] * n
    opp_vals = df["opp"].tolist() if "opp" in df.columns else [""] * n
    df["is_home"] = [parse_home_flag(loc, opp) for loc, opp in zip(loc_vals, opp_vals)]
    if "opp" in df.columns:
        df["opp"] = df["opp"].astype(str).str.replace(r"^[@vsVS.\s]+", "", regex=True).str.strip()

    return df.reset_index(drop=True)


def parse_match_date(value: object) -> Optional[str]:
    """Normalize a Hackastat date to YYYY-MM-DD. Day comes before month."""
    text = str(value or "").strip()
    match = re.search(r"(\d{4})-(\d{2})-(\d{2})", text)
    if match:
        year, month, day = (int(part) for part in match.groups())
        try:
            return datetime(year, month, day).date().isoformat()
        except ValueError:
            return None
    match = re.search(r"(\d{1,2})[./-](\d{1,2})[./-](\d{2,4})", text)
    if match:
        day, month, year = (int(part) for part in match.groups())
        if year < 100:
            year += 2000
        try:
            return datetime(year, month, day).date().isoformat()
        except ValueError:
            return None
    return None


def _opp_key(name: object) -> str:
    text = str(name or "").lower()
    text = re.sub(r"^[@vs.\s]+", "", text)
    return re.sub(r"[^a-z0-9]", "", text)


def _lookup_pace(date_key: object, opp_key: str, grouped: pd.Series) -> Optional[float]:
    if not date_key:
        return None
    exact = (date_key, opp_key)
    if exact in grouped.index:
        return float(grouped.loc[exact])
    hits: list[float] = []
    for (row_date, row_opp), value in grouped.items():
        if row_date != date_key or not opp_key or not row_opp:
            continue
        if opp_key in row_opp or row_opp in opp_key:
            hits.append(float(value))
    if len(hits) == 1:
        return hits[0]
    return None


def attach_pace(
    player_games: pd.DataFrame,
    pace_games: pd.DataFrame,
    opponent_required: bool = False,
) -> tuple[pd.DataFrame, int]:
    """Copy pace from another game log onto the player log, by date and opponent.

    Euroleague still fills a date when that date has only one pace row.
    NBA passes opponent_required=True so a different team's log cannot
    attach just because the games were on the same night.
    """
    if "pace" not in pace_games.columns:
        raise ValueError("The Exp Pace link has no pace column.")
    if "date" not in player_games.columns or "date" not in pace_games.columns:
        raise ValueError("Could not align pace: a match date column is missing.")

    left = player_games.copy()
    left["_dk"] = left["date"].map(parse_match_date)
    src = pace_games.loc[pace_games["pace"].notna()].copy()
    src["_dk"] = src["date"].map(parse_match_date)
    src = src.dropna(subset=["_dk"])
    pace_out = pd.Series(float("nan"), index=left.index, dtype="float64")

    if "opp" in left.columns and "opp" in src.columns:
        src["_ok"] = src["opp"].map(_opp_key)
        grouped = src.groupby(["_dk", "_ok"])["pace"].median()
        for i, (dk, opp) in enumerate(zip(left["_dk"], left["opp"].map(_opp_key))):
            found = _lookup_pace(dk, opp, grouped)
            if found is not None:
                pace_out.iloc[i] = found

    if not opponent_required:
        by_date = src.groupby("_dk")["pace"].agg(["median", "nunique"])
        single = by_date[by_date["nunique"] <= 1]["median"]
        for i, dk in enumerate(left["_dk"]):
            if pd.isna(pace_out.iloc[i]) and dk in single.index:
                pace_out.iloc[i] = float(single.loc[dk])

    left["pace"] = pd.to_numeric(pace_out, errors="coerce")
    matched = int(left["pace"].notna().sum())
    return left.drop(columns=["_dk"]), matched


def _dump_table_js() -> str:
    return """
    () => {
      const root = document.querySelector('#player-gbg')
        || document.querySelector('[id*="player-gbg"]')
        || document.querySelector('.dataTables_wrapper')
        || document.body;
      const table = document.querySelector('#player-gbg table')
        || (root && root.querySelector && root.querySelector('table'))
        || document.querySelector('table#player-gbg')
        || document.querySelector('table');
      if (!table) return null;
      const realTable = table.tagName === 'TABLE' ? table : table.querySelector('table') || table;
      const headers = Array.from(
        realTable.querySelectorAll('thead th, thead td, tr:first-child th, tr:first-child td')
      ).map(el => (el.innerText || '').trim());
      const bodyRows = realTable.querySelectorAll('tbody tr');
      const rowsSrc = bodyRows.length ? bodyRows : realTable.querySelectorAll('tr');
      const rows = [];
      rowsSrc.forEach((tr, idx) => {
        if (!bodyRows.length && idx === 0) return;
        const cells = Array.from(tr.querySelectorAll('td, th')).map(el => (el.innerText || '').trim());
        if (cells.length && cells.some(c => c !== '')) rows.push(cells);
      });
      return { headers, rows };
    }
    """


def _payload_to_frame(payload: object) -> pd.DataFrame:
    if not payload or not isinstance(payload, dict) or not payload.get("rows"):
        return pd.DataFrame()
    headers = [h or f"col_{i}" for i, h in enumerate(payload.get("headers") or [])]
    rows = payload["rows"]
    width = max(len(headers), max(len(r) for r in rows))
    headers = (headers + [f"col_{i}" for i in range(len(headers), width)])[:width]
    norm_rows = [r + [""] * (width - len(r)) for r in rows]
    return pd.DataFrame(norm_rows, columns=headers)


def _score_gbg_frame(df: pd.DataFrame) -> int:
    """Prefer tables that look like a player game log."""
    if df is None or df.empty:
        return -1
    cols = " ".join(str(c).lower() for c in df.columns)
    score = len(df)
    for token in ("pts", "min", "fga", "opp", "player", "reb", "ast", "poss", "pace", "date"):
        if token in cols:
            score += 25
    return score


def _frames_from_html(html: str) -> list[pd.DataFrame]:
    try:
        frames = pd.read_html(html)
    except ValueError:
        return []
    except Exception:
        return []
    return [f for f in frames if f is not None and not f.empty]

from config import CHROME_PROFILE_DIR

CLOUDFLARE_MARKERS = (
    "just a moment",
    "performing security verification",
    "verify you are human",
    "verify you are not a bot",
    "cf-challenge",
    "attention required",
)


def _page_looks_like_cloudflare(page) -> bool:
    try:
        title = (page.title() or "").lower()
        url = (page.url or "").lower()
        body = ""
        try:
            body = (page.inner_text("body", timeout=1500) or "").lower()
        except Exception:
            pass
        blob = f"{title}\n{url}\n{body}"
        return any(marker in blob for marker in CLOUDFLARE_MARKERS)
    except Exception:
        return False


def _table_ready(page) -> bool:
    try:
        loc = page.locator("#player-gbg, #player-gbg table, table").first
        return loc.is_visible(timeout=500)
    except Exception:
        return False


def _wait_out_cloudflare(page, wait_ms: int = 180000) -> None:
    """Wait while Cloudflare is showing. Never click Turnstile from Playwright.

    Automated clicks on the checkbox cause an infinite reload loop.
    """
    if not _page_looks_like_cloudflare(page):
        return
    print()
    print("=" * 72)
    print("CLOUDFLARE blocked Playwright Chrome (reload loop on the checkbox).")
    print("Do not keep clicking inside this automated window.")
    print()
    print("Do this instead:")
    print("  1. Close the script (Ctrl+C) and close the automated Chrome.")
    print("  2. Open a REAL Chrome with remote debugging. In PowerShell:")
    print(r'     & "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222 --user-data-dir="$PWD\.hackastat_chrome"')
    print("  3. In that Chrome open hackastat.eu and pass Verify you are human.")
    print("  4. Leave Chrome open, then run:")
    print("     python run_daily_bets.py --cdp http://127.0.0.1:9222")
    print()
    print("Waiting up to 3 minutes in case it auto-passes...")
    print("=" * 72)
    elapsed = 0
    step = 1000
    while elapsed < wait_ms:
        if not _page_looks_like_cloudflare(page) or _table_ready(page):
            print("Challenge cleared.")
            return
        page.wait_for_timeout(step)
        elapsed += step
        if elapsed in {15000, 30000, 60000, 120000}:
            print(f"  still waiting... {elapsed // 1000}s")
    print("Cloudflare still present after wait.")


def _dismiss_cookies(page) -> None:
    for sel in (
        "button:has-text('Accept')",
        "button:has-text('I agree')",
        "button:has-text('Accetta')",
        "#onetrust-accept-btn-handler",
    ):
        try:
            page.locator(sel).first.click(timeout=1500)
        except Exception:
            pass


def _extract_from_page(page) -> pd.DataFrame:
    html = page.content()
    payload = None
    try:
        payload = page.evaluate(_dump_table_js())
    except Exception:
        payload = None
    candidates: list[pd.DataFrame] = []
    dumped = _payload_to_frame(payload)
    if not dumped.empty:
        candidates.append(dumped)
    candidates.extend(_frames_from_html(html))
    if not candidates:
        return pd.DataFrame()
    raw = max(candidates, key=_score_gbg_frame)
    cleaned = clean_game_log(raw)
    if cleaned.empty:
        return raw.reset_index(drop=True)
    return cleaned


class HackastatSession:
    """Reuse one Chrome session so Cloudflare cookies persist across players."""

    def __init__(self, headed: bool = True, cdp_url: str = "", timeout_ms: int = 60000) -> None:
        self.headed = headed
        self.cdp_url = (cdp_url or "").strip()
        self.timeout_ms = timeout_ms
        self._pw = None
        self._browser = None
        self._context = None
        self._owns_browser = True

    def __enter__(self) -> "HackastatSession":
        self._pw = sync_playwright().start()
        if self.cdp_url:
            print(f"Connecting to existing Chrome at {self.cdp_url}")
            self._browser = self._pw.chromium.connect_over_cdp(self.cdp_url)
            if self._browser.contexts:
                self._context = self._browser.contexts[0]
            else:
                self._context = self._browser.new_context()
            self._owns_browser = False
            return self

        CHROME_PROFILE_DIR.mkdir(parents=True, exist_ok=True)
        launch_args = {
            "user_data_dir": str(CHROME_PROFILE_DIR),
            "headless": not self.headed,
            "viewport": {"width": 1920, "height": 1080},
            "locale": "en-US",
            "ignore_https_errors": True,
            "args": [
                "--disable-blink-features=AutomationControlled",
                "--start-maximized",
            ],
        }
        try:
            self._context = self._pw.chromium.launch_persistent_context(
                channel="chrome",
                **launch_args,
            )
            print(f"Using persistent Chrome profile: {CHROME_PROFILE_DIR}")
        except Exception:
            self._context = self._pw.chromium.launch_persistent_context(**launch_args)
            print("Using Playwright Chromium profile. Prefer --cdp with real Chrome.")
        self._owns_browser = True
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        try:
            if self._owns_browser and self._context:
                self._context.close()
        finally:
            if self._pw:
                self._pw.stop()

    def _page(self):
        if self._context.pages:
            return self._context.pages[-1]
        return self._context.new_page()

    def scrape(self, url: str, *, player_table: bool = True) -> pd.DataFrame:
        if not url or not str(url).strip():
            raise ValueError("Missing Hackastat URL.")
        page = self._page()
        page.goto(url.strip(), wait_until="domcontentloaded", timeout=self.timeout_ms)
        _dismiss_cookies(page)
        _wait_out_cloudflare(page, wait_ms=180000)
        try:
            if player_table:
                page.locator("#player-gbg").wait_for(state="visible", timeout=self.timeout_ms)
            else:
                page.locator("#player-gbg, table").first.wait_for(
                    state="visible", timeout=self.timeout_ms
                )
        except PlaywrightTimeout:
            try:
                page.locator("table").first.wait_for(state="visible", timeout=15000)
            except PlaywrightTimeout as exc:
                raise RuntimeError(
                    "Could not pass Cloudflare. Start real Chrome with "
                    "--remote-debugging-port=9222, complete the checkbox there, "
                    "then: python run_daily_bets.py --cdp http://127.0.0.1:9222"
                ) from exc
        page.wait_for_timeout(1500)
        df = _extract_from_page(page)
        if df.empty:
            raise RuntimeError(f"No game-log table found at {url}")
        return df


def scrape_player_gbg(
    url: str,
    timeout_ms: int = 60000,
    headed: bool = True,
    cdp_url: str = "",
) -> pd.DataFrame:
    with HackastatSession(headed=headed, cdp_url=cdp_url, timeout_ms=timeout_ms) as session:
        return session.scrape(url)
