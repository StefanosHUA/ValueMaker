"""Load Stoiximan league pages in Playwright Chrome and print bet-builder JSON for one date."""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from playwright.sync_api import TimeoutError as PlaywrightTimeout
from playwright.sync_api import sync_playwright

BASE = "https://www.stoiximan.gr"
STATE_MARK = 'window["initial_state"]='
GEO_MARKERS = (
    "cannot be accessed from your location",
    "δεν επιτρέπεται η χρήση των υπηρεσιών",
)
LEAGUES = (
    ("NBA", "/sport/basket/nba/441g/", "America/New_York"),
    ("EL", "/sport/basket/euroleague/euroleague/439g/", "Europe/Athens"),
    ("EC", "/sport/basket/eurocup/eurocup/455g/", "Europe/Athens"),
)
GEO_ERROR = (
    "Stoiximan showed the blue location page. Stay on home Wi-Fi (no hotspot) and press Fetch once."
)


def is_geo_blocked(html: str) -> bool:
    low = html.lower()
    return any(marker.lower() in low for marker in GEO_MARKERS)


def collect_events(node, found: dict, names: dict) -> None:
    if isinstance(node, dict):
        url = str(node.get("url") or "")
        if "startTime" in node and "id" in node and "/apodoseis/" in url:
            eid = int(node["id"])
            found[eid] = int(node["startTime"])
            if node.get("name"):
                names[eid] = node["name"]
        for value in node.values():
            collect_events(value, found, names)
    elif isinstance(node, list):
        for value in node:
            collect_events(value, found, names)


def initial_state(html: str) -> dict:
    i = html.find(STATE_MARK)
    if i < 0:
        raise RuntimeError("blocked")
    raw = html[i + len(STATE_MARK) :]
    obj, _ = json.JSONDecoder().raw_decode(raw)
    return obj


def wait_league(page, path: str) -> dict:
    page.goto(BASE + path, wait_until="domcontentloaded", timeout=45_000)
    html = page.content()
    if is_geo_blocked(html):
        raise RuntimeError("geo")
    if STATE_MARK not in html:
        page.wait_for_function(
            "() => document.documentElement.outerHTML.includes('window[\"initial_state\"]=')",
            timeout=45_000,
        )
        html = page.content()
        if is_geo_blocked(html):
            raise RuntimeError("geo")
    return initial_state(html)


def event_payload(page, eid: int) -> dict | None:
    url = f"{BASE}/api/betbuilderplus/event?id={eid}&sportCode=BASK"
    data = page.evaluate(
        """async (url) => {
            const r = await fetch(url, { credentials: 'include' });
            if (!r.ok) return { error: r.status };
            return await r.json();
        }""",
        url,
    )
    if not data or data.get("error"):
        print(f"event {eid} HTTP {data.get('error') if data else 'empty'}", file=sys.stderr)
        return None
    return data


def fetch_date(day: str, headed: bool) -> dict:
    want = datetime.strptime(day, "%Y-%m-%d").date()
    events_out: list[dict] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not headed)
        context = browser.new_context(locale="el-GR", viewport={"width": 1280, "height": 800})
        page = context.new_page()
        try:
            for code, path, zone in LEAGUES:
                try:
                    state = wait_league(page, path)
                except PlaywrightTimeout:
                    return {
                        "error": "Stoiximan did not load in time. Press Fetch once more.",
                        "events": [],
                    }
                except Exception as e:
                    if "geo" in str(e) or "blocked" in str(e):
                        return {"error": GEO_ERROR, "events": []}
                    print(f"league {code} failed: {e}", file=sys.stderr)
                    continue
                found: dict[int, int] = {}
                names: dict[int, str] = {}
                collect_events(state, found, names)
                tz = ZoneInfo(zone)
                for eid, start_ms in found.items():
                    event_date = datetime.fromtimestamp(start_ms / 1000, tz=timezone.utc).astimezone(tz).date()
                    if event_date != want:
                        continue
                    time.sleep(0.5)
                    payload = event_payload(page, eid)
                    if payload is None:
                        continue
                    events_out.append(
                        {
                            "league": code,
                            "id": str(eid),
                            "name": names.get(eid),
                            "payload": payload,
                        }
                    )
        finally:
            context.close()
            browser.close()
    return {"error": None, "events": events_out}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", required=True)
    ap.add_argument("--headed", action="store_true", default=True)
    ap.add_argument("--headless", action="store_true")
    args = ap.parse_args()
    headed = not args.headless
    try:
        result = fetch_date(args.date, headed)
    except Exception as e:
        result = {"error": str(e), "events": []}
    json.dump(result, sys.stdout, ensure_ascii=False)
    sys.stdout.write("\n")
    if result.get("error"):
        sys.exit(2)


if __name__ == "__main__":
    main()
