package com.fermabets.service;

import com.fermabets.domain.GameRow;
import com.fermabets.domain.TeamGame;
import com.fermabets.model.LeagueData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public class DataRepository {
    private final JdbcTemplate jdbc;

    public DataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public LeagueData loadLeague(String league) {
        List<GameRow> rows = jdbc.query(
                "SELECT game_id, player_id, game_date, team, opp, is_home, minutes, pts, reb, ast, fg3m, fg3a, fga, fta,"
                        + " stl, blk, tov FROM player_game WHERE league=?",
                (rs, i) -> new GameRow(rs.getString(1), rs.getString(2), league, LocalDate.parse(rs.getString(3)),
                        rs.getString(4), rs.getString(5), rs.getInt(6) == 1, rs.getDouble(7),
                        new double[]{rs.getDouble(8), rs.getDouble(9), rs.getDouble(10), rs.getDouble(11),
                                rs.getDouble(12), rs.getDouble(13), rs.getDouble(14), rs.getDouble(15),
                                rs.getDouble(16), rs.getDouble(17)}),
                league);
        List<TeamGame> games = jdbc.query(
                "SELECT game_id, team, opp, game_date, is_home, pts, poss, fta, pf FROM team_game WHERE league=?",
                (rs, i) -> new TeamGame(rs.getString(1), rs.getString(2), rs.getString(3),
                        LocalDate.parse(rs.getString(4)), rs.getInt(5) == 1, rs.getDouble(6), rs.getDouble(7),
                        rs.getDouble(8), rs.getDouble(9)),
                league);
        Map<String, List<String>> officials = new HashMap<>();
        jdbc.query("SELECT o.game_id, o.official FROM game_official o JOIN team_game t ON t.game_id=o.game_id"
                        + " WHERE t.league=? GROUP BY o.game_id, o.official",
                rs -> {
                    officials.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                }, league);
        return new LeagueData(league, rows, games, officials);
    }

    public record Player(String id, String name, String key, String lastTeam, LocalDate lastDate) {
    }

    public List<Player> players(String league) {
        return jdbc.query(
                "SELECT p.id, p.name, p.name_key, g.team, g.game_date FROM player p JOIN player_game g ON g.player_id=p.id"
                        + " WHERE p.league=? AND g.game_date=(SELECT MAX(game_date) FROM player_game WHERE player_id=p.id)"
                        + " GROUP BY p.id",
                (rs, i) -> new Player(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        LocalDate.parse(rs.getString(5))),
                league);
    }

    public record Fixture(String league, LocalDate date, String home, String away) {
    }

    public List<Fixture> schedule(LocalDate date) {
        return jdbc.query("SELECT league, game_date, home, away FROM schedule WHERE game_date=? ORDER BY league, home",
                (rs, i) -> new Fixture(rs.getString(1), LocalDate.parse(rs.getString(2)), rs.getString(3),
                        rs.getString(4)),
                date.toString());
    }

    public record GameMarket(double margin, Double total, Double homeWin, String bookmaker) {
    }

    /** Market-implied expected margin (home perspective) and total for a fixture, if a bookmaker has priced it. */
    public Optional<GameMarket> gameMarket(String league, LocalDate date, String home, String away) {
        return jdbc.query("SELECT margin, total, home_win, bookmaker FROM game_line WHERE league=? AND game_date=?"
                                + " AND home=? AND away=? AND margin IS NOT NULL ORDER BY fetched_at DESC",
                        (rs, i) -> new GameMarket(rs.getDouble(1), (Double) rs.getObject(2), (Double) rs.getObject(3),
                                rs.getString(4)),
                        league, date.toString(), home, away)
                .stream().findFirst();
    }

    public void saveGameMarket(String league, LocalDate date, String home, String away, String bookmaker,
                               String fetchedAt, double margin, Double total, Double homeWin) {
        jdbc.update("INSERT OR REPLACE INTO game_line(league, game_date, home, away, bookmaker, fetched_at, margin,"
                + " total, home_win) VALUES (?,?,?,?,?,?,?,?,?)", league, date.toString(), home, away, bookmaker,
                fetchedAt, margin, total, homeWin);
    }

    public void saveSpread(String league, LocalDate date, String home, String away, String source, String fetchedAt,
                           double homeSpread, Double total) {
        jdbc.update("INSERT OR REPLACE INTO game_spread(league, game_date, home, away, source, fetched_at, home_spread,"
                + " total) VALUES (?,?,?,?,?,?,?,?)", league, date.toString(), home, away, source, fetchedAt,
                homeSpread, total);
    }

    public record Spread(String home, String away, double homeSpread, String source) {
        /** Handicap from this team's side: negative means favourite. */
        public double forTeam(String team) {
            return team.equals(home) ? homeSpread : -homeSpread;
        }
    }

    /** Main handicap of the game; the bookmaker (Stoiximan) wins over RotoWire. */
    public Optional<Spread> spread(String league, LocalDate date, String home, String away) {
        return jdbc.query("SELECT home, away, home_spread, source FROM game_spread WHERE league=? AND game_date=?"
                                + " AND home=? AND away=? ORDER BY CASE source WHEN 'stoiximan' THEN 0 ELSE 1 END,"
                                + " fetched_at DESC",
                        (rs, i) -> new Spread(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getString(4)),
                        league, date.toString(), home, away)
                .stream().findFirst();
    }

    public record Injury(String team, String name, String key, String status, boolean starter) {
    }

    public List<Injury> injuries(String league, LocalDate date, String team) {
        return jdbc.query("SELECT team, player_name, name_key, status, starter FROM injury WHERE league=?"
                        + " AND game_date=? AND team=? ORDER BY starter DESC, player_name",
                (rs, i) -> new Injury(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5) == 1),
                league, date.toString(), team);
    }

    public boolean hasInjuryData(String league, LocalDate date) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM injury WHERE league=? AND game_date=?", Integer.class,
                league, date.toString());
        return n != null && n > 0;
    }

    public Optional<String> playerKey(String playerId) {
        return jdbc.queryForList("SELECT name_key FROM player WHERE id=?", String.class, playerId).stream()
                .findFirst();
    }

    public Optional<Fixture> fixtureFor(String league, LocalDate date, String team) {
        return jdbc.query("SELECT league, game_date, home, away FROM schedule WHERE league=? AND game_date=?"
                                + " AND (home=? OR away=?)",
                        (rs, i) -> new Fixture(rs.getString(1), LocalDate.parse(rs.getString(2)), rs.getString(3),
                                rs.getString(4)),
                        league, date.toString(), team, team)
                .stream().findFirst();
    }

    public Set<String> absentees(LocalDate date) {
        return new HashSet<>(jdbc.queryForList("SELECT player_id FROM absence WHERE game_date=?", String.class,
                date.toString()));
    }

    public List<String> crew(String league, LocalDate date, String home) {
        return jdbc.queryForList("SELECT official FROM ref_assignment WHERE league=? AND game_date=? AND home=?",
                String.class, league, date.toString(), home);
    }

    public Optional<String> setting(String key) {
        return jdbc.queryForList("SELECT value FROM setting WHERE key=?", String.class, key).stream().findFirst();
    }

    public void saveSetting(String key, String value) {
        jdbc.update("INSERT OR REPLACE INTO setting(key, value) VALUES (?,?)", key, value);
    }
}
