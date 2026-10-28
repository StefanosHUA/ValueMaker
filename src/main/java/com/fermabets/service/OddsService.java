package com.fermabets.service;

import com.fermabets.domain.Stat;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class OddsService {
    /** A bookmaker adapter. Register a Spring bean implementing this to add a source for automatic refresh. */
    public interface OddsProvider {
        String bookmaker();

        List<OddsLine> fetch(LocalDate date) throws Exception;

        /** Game-level prices (spread/total) gathered during the last fetch; optional. */
        default List<GameLine> gameLines() {
            return List.of();
        }
    }

    /** Expected home margin and total points implied by a bookmaker. Groups tie it to the props roster sides. */
    public record GameLine(String bookmaker, String league, LocalDate date, String homeGroup, String awayGroup,
                           double margin, Double total, Double homeWin) {
    }

    public record OddsLine(String bookmaker, LocalDate date, String playerName, Stat stat, double line,
                           Double over, Double under, String group) {
        /** group = a bookmaker event + team side; lets the importer reject same-name players from other games. */
        public OddsLine(String bookmaker, LocalDate date, String playerName, Stat stat, double line, Double over,
                        Double under) {
            this(bookmaker, date, playerName, stat, line, over, under, null);
        }
    }

    public record OddsRow(long id, String bookmaker, String league, LocalDate date, String playerName,
                          String playerId, Stat stat, double line, Double over, Double under) {
    }

    public record ImportResult(int imported, List<String> problems) {
    }

    private final DataRepository repo;
    private final List<OddsProvider> providers;

    public OddsService(DataRepository repo, List<OddsProvider> providers) {
        this.repo = repo;
        this.providers = providers;
    }

    public int providerCount() {
        return providers.size();
    }

    public ImportResult refreshFromProviders(LocalDate date) {
        int total = 0;
        List<String> problems = new ArrayList<>();
        if (providers.isEmpty()) {
            problems.add("No automatic bookmaker adapters are registered yet. Use the paste/CSV import below.");
        }
        for (OddsProvider p : providers) {
            try {
                List<OddsLine> fetched = p.fetch(date);
                ImportResult r = save(fetched, p.gameLines());
                total += r.imported();
                problems.addAll(r.problems());
            } catch (Exception e) {
                problems.add(p.bookmaker() + ": " + e.getMessage());
            }
        }
        return new ImportResult(total, problems);
    }

    /** Lines: date,bookmaker,player,stat,line,over,under (comma, semicolon or tab separated; header optional). */
    public ImportResult importCsv(String text, String defaultBookmaker, LocalDate defaultDate) {
        List<OddsLine> lines = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int lineNo = 0;
        for (String raw : text.split("\\R")) {
            lineNo++;
            String row = raw.trim();
            if (row.isEmpty() || row.toLowerCase(Locale.ROOT).startsWith("date")) {
                continue;
            }
            String[] c = row.split("[;\\t,]");
            try {
                lines.add(parseRow(c, defaultBookmaker, defaultDate));
            } catch (RuntimeException e) {
                problems.add("line " + lineNo + ": " + e.getMessage() + " -> " + row);
            }
        }
        ImportResult saved = save(lines);
        problems.addAll(saved.problems());
        return new ImportResult(saved.imported(), problems);
    }

    static OddsLine parseRow(String[] c, String defaultBookmaker, LocalDate defaultDate) {
        if (c.length < 6) {
            throw new IllegalArgumentException("expected date,bookmaker,player,stat,line,over[,under]");
        }
        LocalDate date = c[0].isBlank() ? defaultDate : LocalDate.parse(c[0].trim());
        String book = c[1].isBlank() ? defaultBookmaker : c[1].trim();
        Stat stat = Stat.parse(c[3]).orElseThrow(() -> new IllegalArgumentException("unknown stat '" + c[3] + "'"));
        double line = Double.parseDouble(c[4].trim().replace(',', '.'));
        Double over = parseOdds(c[5]);
        Double under = c.length > 6 ? parseOdds(c[6]) : null;
        if (over == null && under == null) {
            throw new IllegalArgumentException("no odds");
        }
        return new OddsLine(book, date, c[2].trim(), stat, line, over, under);
    }

    private static Double parseOdds(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        double v = Double.parseDouble(s.trim().replace(',', '.'));
        if (v <= 1.0) {
            throw new IllegalArgumentException("odds must be > 1");
        }
        return v;
    }

    private ImportResult save(List<OddsLine> lines) {
        return save(lines, List.of());
    }

    private ImportResult save(List<OddsLine> lines, List<GameLine> gameLines) {
        Map<String, List<DataRepository.Player>> byLeague = new LinkedHashMap<>();
        for (String league : List.of("NBA", "EL", "EC")) {
            byLeague.put(league, repo.players(league));
        }
        List<String> problems = new ArrayList<>();
        int n = 0;
        String now = Instant.now().toString();
        record Matched(OddsLine line, DataRepository.Player player, String league) {
        }
        List<Matched> matched = new ArrayList<>();
        Set<String> missing = new TreeSet<>();
        for (OddsLine l : lines) {
            DataRepository.Player match = null;
            String league = null;
            for (var e : byLeague.entrySet()) {
                match = PlayerMatcher.match(e.getValue(), l.playerName());
                if (match != null) {
                    league = e.getKey();
                    break;
                }
            }
            if (match == null) {
                missing.add(l.playerName());
                continue;
            }
            matched.add(new Matched(l, match, league));
        }
        // A bookmaker roster side is one team: the majority team of its known players. Players whose database team
        // differs are namesakes from other games (or traded), and must not get this line.
        Map<String, Map<String, Integer>> teamVotes = new HashMap<>();
        Set<String> seenVote = new HashSet<>();
        for (Matched m : matched) {
            if (m.line().group() != null && m.player().lastTeam() != null
                    && seenVote.add(m.line().group() + "|" + m.player().id())) {
                teamVotes.computeIfAbsent(m.line().group(), k -> new HashMap<>())
                        .merge(m.league() + ":" + m.player().lastTeam(), 1, Integer::sum);
            }
        }
        Map<String, String> groupTeam = new HashMap<>();
        teamVotes.forEach((g, votes) -> groupTeam.put(g,
                Collections.max(votes.entrySet(), Map.Entry.comparingByValue()).getKey()));
        for (GameLine g : gameLines) {
            String home = groupTeam.get(g.homeGroup()), away = groupTeam.get(g.awayGroup());
            String prefix = g.league() + ":";
            if (home != null && away != null && home.startsWith(prefix) && away.startsWith(prefix)
                    && !home.equals(away)) {
                home = home.substring(prefix.length());
                away = away.substring(prefix.length());
                repo.saveGameMarket(g.league(), g.date(), home, away, g.bookmaker(), now, g.margin(), g.total(),
                        g.homeWin());
            }
        }
        for (String name : missing) {
            problems.add("player not found in database: " + name);
        }
        for (Matched m : matched) {
            OddsLine l = m.line();
            DataRepository.Player match = m.player();
            String league = m.league();
            String expected = l.group() == null ? null : groupTeam.get(l.group());
            if (expected != null && match.lastTeam() != null && !expected.equals(league + ":" + match.lastTeam())) {
                continue;
            }
            List<Map<String, Object>> last = repo.jdbc().queryForList(
                    "SELECT over_odds, under_odds FROM odds WHERE bookmaker=? AND game_date=? AND player_id=? AND stat=?"
                            + " AND line=? ORDER BY id DESC LIMIT 1",
                    l.bookmaker(), l.date().toString(), match.id(), l.stat().name(), l.line());
            if (!last.isEmpty() && Objects.equals(toDouble(last.get(0).get("over_odds")), l.over())
                    && Objects.equals(toDouble(last.get(0).get("under_odds")), l.under())) {
                n++;
                continue;
            }
            repo.jdbc().update("INSERT INTO odds(fetched_at, league, bookmaker, game_date, player_name, player_id, stat,"
                            + " line, over_odds, under_odds) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    now, league, l.bookmaker(), l.date().toString(), match.name(), match.id(), l.stat().name(),
                    l.line(), l.over(), l.under());
            n++;
        }
        return new ImportResult(n, problems);
    }

    public Optional<String> lastFetch(LocalDate date) {
        return repo.jdbc().queryForList("SELECT MAX(fetched_at) FROM odds WHERE game_date=?", String.class,
                date.toString()).stream().filter(Objects::nonNull).findFirst();
    }

    private static Double toDouble(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }

    /** Opening (first seen) odds for the same book/player/stat/line, used to show line movement and CLV. */
    public Optional<OddsRow> opening(OddsRow row) {
        return repo.jdbc().query(SELECT + " WHERE bookmaker=? AND game_date=? AND player_id=? AND stat=? AND line=?"
                        + " ORDER BY id ASC LIMIT 1", ROW_MAPPER, row.bookmaker(), row.date().toString(),
                row.playerId(), row.stat().name(), row.line()).stream().findFirst();
    }

    /** Latest odds for a bet's exact selection (the closest thing to closing odds we have). */
    public Optional<OddsRow> latest(String bookmaker, LocalDate date, String playerId, Stat stat, double line) {
        return repo.jdbc().query(SELECT + " WHERE bookmaker=? AND game_date=? AND player_id=? AND stat=? AND line=?"
                        + " ORDER BY id DESC LIMIT 1", ROW_MAPPER, bookmaker, date.toString(), playerId, stat.name(),
                line).stream().findFirst();
    }

    private static final String SELECT = "SELECT id, bookmaker, league, game_date, player_name, player_id, stat, line,"
            + " over_odds, under_odds FROM odds";
    private static final org.springframework.jdbc.core.RowMapper<OddsRow> ROW_MAPPER = (rs, i) -> new OddsRow(
            rs.getLong(1), rs.getString(2), rs.getString(3), LocalDate.parse(rs.getString(4)), rs.getString(5),
            rs.getString(6), Stat.valueOf(rs.getString(7)), rs.getDouble(8),
            rs.getObject(9) == null ? null : rs.getDouble(9), rs.getObject(10) == null ? null : rs.getDouble(10));

    /** Latest snapshot per bookmaker/player/stat/line. */
    public List<OddsRow> forDate(LocalDate date) {
        return repo.jdbc().query(SELECT + " WHERE id IN (SELECT MAX(id) FROM odds WHERE game_date=?"
                + " GROUP BY bookmaker, player_id, stat, line) ORDER BY player_name, stat, line", ROW_MAPPER,
                date.toString());
    }
    public void clear(LocalDate date) {
        repo.jdbc().update("DELETE FROM odds WHERE game_date=?", date.toString());
    }
}
