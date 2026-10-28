"""Shared helpers for the ingestion scripts (SQLite shared with the Java app)."""
from __future__ import annotations

import re
import sqlite3
import sys
import unicodedata
from pathlib import Path

SCHEMA = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "schema.sql"


def log(msg: str) -> None:
    print(msg, flush=True)


def connect(db_path: str) -> sqlite3.Connection:
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(db_path, timeout=60)
    con.execute("PRAGMA journal_mode=WAL")
    con.executescript(SCHEMA.read_text(encoding="utf-8"))
    return con


def name_key(name: str) -> str:
    """Accent/case/punctuation-insensitive key; 'LASTNAME, FIRST' is flipped."""
    name = str(name or "").strip()
    if "," in name:
        last, first = [p.strip() for p in name.split(",", 1)]
        name = f"{first} {last}"
    text = unicodedata.normalize("NFKD", name)
    text = "".join(c for c in text if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]", "", text.lower())


def pretty_name(name: str) -> str:
    name = str(name or "").strip()
    if "," in name:
        last, first = [p.strip() for p in name.split(",", 1)]
        return f"{first.title()} {last.title()}"
    return name


def num(value, default=0.0) -> float:
    try:
        if value is None or value != value:
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def fail(msg: str) -> None:
    log(f"ERROR: {msg}")
    sys.exit(1)
