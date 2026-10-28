CREATE TABLE IF NOT EXISTS player (
    id TEXT PRIMARY KEY,
    league TEXT NOT NULL,
    name TEXT NOT NULL,
    name_key TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_player_key ON player(name_key);

CREATE TABLE IF NOT EXISTS player_game (
    game_id TEXT NOT NULL,
    player_id TEXT NOT NULL,
    league TEXT NOT NULL,
    season TEXT NOT NULL,
    game_date TEXT NOT NULL,
    team TEXT NOT NULL,
    opp TEXT NOT NULL,
    is_home INTEGER NOT NULL,
    minutes REAL NOT NULL,
    pts REAL, reb REAL, ast REAL, fg3m REAL, fg3a REAL,
    fga REAL, fta REAL, stl REAL, blk REAL, tov REAL,
    PRIMARY KEY (game_id, player_id)
);
CREATE INDEX IF NOT EXISTS idx_pg_player ON player_game(player_id, game_date);
CREATE INDEX IF NOT EXISTS idx_pg_team ON player_game(league, team, game_date);

CREATE TABLE IF NOT EXISTS team_game (
    game_id TEXT NOT NULL,
    league TEXT NOT NULL,
    team TEXT NOT NULL,
    opp TEXT NOT NULL,
    game_date TEXT NOT NULL,
    is_home INTEGER NOT NULL,
    pts REAL, poss REAL, fta REAL, pf REAL, minutes REAL,
    PRIMARY KEY (game_id, team)
);

CREATE TABLE IF NOT EXISTS game_official (
    game_id TEXT NOT NULL,
    official TEXT NOT NULL,
    PRIMARY KEY (game_id, official)
);

CREATE TABLE IF NOT EXISTS schedule (
    league TEXT NOT NULL,
    game_date TEXT NOT NULL,
    home TEXT NOT NULL,
    away TEXT NOT NULL,
    PRIMARY KEY (league, game_date, home, away)
);

-- Manual or imported absences for upcoming games (players who will not play).
CREATE TABLE IF NOT EXISTS absence (
    player_id TEXT NOT NULL,
    game_date TEXT NOT NULL,
    note TEXT,
    PRIMARY KEY (player_id, game_date)
);

CREATE TABLE IF NOT EXISTS ref_assignment (
    league TEXT NOT NULL,
    game_date TEXT NOT NULL,
    home TEXT NOT NULL,
    official TEXT NOT NULL,
    PRIMARY KEY (league, game_date, home, official)
);

CREATE TABLE IF NOT EXISTS odds (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fetched_at TEXT NOT NULL,
    league TEXT NOT NULL,
    bookmaker TEXT NOT NULL,
    game_date TEXT NOT NULL,
    player_name TEXT NOT NULL,
    player_id TEXT,
    stat TEXT NOT NULL,
    line REAL NOT NULL,
    over_odds REAL,
    under_odds REAL
);
CREATE INDEX IF NOT EXISTS idx_odds_date ON odds(game_date);

-- Latest market view of a game (from a bookmaker): expected home margin and total points, de-vigged.
CREATE TABLE IF NOT EXISTS game_line (
    league TEXT NOT NULL,
    game_date TEXT NOT NULL,
    home TEXT NOT NULL,
    away TEXT NOT NULL,
    bookmaker TEXT NOT NULL,
    fetched_at TEXT NOT NULL,
    margin REAL,
    total REAL,
    home_win REAL,
    PRIMARY KEY (league, game_date, home, away, bookmaker)
);

-- RotoWire lineups: injury status and expected starters for upcoming games (warnings only).
CREATE TABLE IF NOT EXISTS injury (
    league TEXT NOT NULL,
    game_date TEXT NOT NULL,
    team TEXT NOT NULL,
    player_name TEXT NOT NULL,
    name_key TEXT NOT NULL,
    status TEXT,
    starter INTEGER NOT NULL DEFAULT 0,
    fetched_at TEXT NOT NULL,
    PRIMARY KEY (league, game_date, team, name_key)
);

-- Main handicap of a game from the home side (negative = home favourite). Stoiximan wins over RotoWire.
CREATE TABLE IF NOT EXISTS game_spread (
    league TEXT NOT NULL,
    game_date TEXT NOT NULL,
    home TEXT NOT NULL,
    away TEXT NOT NULL,
    source TEXT NOT NULL,
    fetched_at TEXT NOT NULL,
    home_spread REAL NOT NULL,
    total REAL,
    PRIMARY KEY (league, game_date, home, away, source)
);

CREATE TABLE IF NOT EXISTS bet (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    placed_at TEXT NOT NULL,
    game_date TEXT NOT NULL,
    league TEXT NOT NULL,
    bookmaker TEXT NOT NULL,
    player_name TEXT NOT NULL,
    player_id TEXT,
    stat TEXT NOT NULL,
    side TEXT NOT NULL,
    line REAL NOT NULL,
    odds REAL NOT NULL,
    stake REAL NOT NULL,
    model_prob REAL,
    result TEXT NOT NULL DEFAULT 'PENDING',
    actual REAL,
    profit REAL,
    close_odds REAL,
    clv REAL
);

CREATE TABLE IF NOT EXISTS ingest_run (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    league TEXT NOT NULL,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    status TEXT NOT NULL,
    detail TEXT
);

CREATE TABLE IF NOT EXISTS setting (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
