package com.fermabets.service;

import com.fermabets.AppClock;
import com.fermabets.domain.Stat;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Closing-line value tracking. A scheduled job keeps refreshing odds for pending bets, stamps the last price seen before
 * the game as the closing line, settles finished bets and then re-fits a probability calibration from the results.
 */
@Service
public class ClvService {
    private static final Logger log = LoggerFactory.getLogger(ClvService.class);
    static final int MIN_SETTLED_FOR_CALIBRATION = 40;
    static final double MAX_SHIFT = 0.04;

    public record Stats(int clvCount, double avgClv, double beatClose, int settled, double hitRate, double avgProb,
                        double brier, double shift) {
    }

    private final DataRepository repo;
    private final OddsService odds;
    private final BetService bets;
    private final ValueEngine engine;
    private final IngestService ingest;
    private final ModelService models;

    public ClvService(DataRepository repo, OddsService odds, BetService bets, ValueEngine engine,
                      IngestService ingest, ModelService models) {
        this.ingest = ingest;
        this.models = models;
        this.repo = repo;
        this.odds = odds;
        this.bets = bets;
        this.engine = engine;
    }

    @PostConstruct
    void init() {
        repo.jdbc().execute("CREATE TABLE IF NOT EXISTS pick_log (id INTEGER PRIMARY KEY AUTOINCREMENT, game_date TEXT,"
                + " league TEXT, player_id TEXT, player_name TEXT, stat TEXT, line REAL, side TEXT, bookmaker TEXT,"
                + " open_odds REAL, close_odds REAL, p_model REAL, p_final REAL, close_fair REAL, clv REAL,"
                + " result TEXT, actual REAL)");
        repo.jdbc().execute("CREATE TABLE IF NOT EXISTS exclude_game (league TEXT NOT NULL, game_date TEXT NOT NULL,"
                + " home TEXT NOT NULL, away TEXT NOT NULL, reason TEXT,"
                + " PRIMARY KEY (league, game_date, home, away))");
        repo.excludeGame("NBA", LocalDate.of(2026, 10, 31), "UTA", "POR",
                "Live Superbet refresh during UTA-POR; results stay out of the books");
        repo.voidExcludedPicks();
        for (String col : List.of("close_odds REAL", "clv REAL")) {
            try {
                repo.jdbc().execute("ALTER TABLE bet ADD COLUMN " + col);
            } catch (RuntimeException ignored) {
                // column already exists
            }
        }
        refit();
    }

    /** Refit the parallel probability shift from settled pick_log + placed bets. 55/45 stays put. */
    public void refit() {
        engine.setCalibration(stats().shift());
    }

    /** Settlement and calibration only. Odds are fetched when you press Fetch, not on a timer. */
    @Scheduled(initialDelay = 120_000, fixedDelay = 600_000)
    public void run() {
        try {
            LocalDate d0 = AppClock.today();
            int settled = bets.settlePending() + settlePicks();
            int closed = logPicks(d0.minusDays(1)) + logPicks(d0) + captureClosing();
            settled += settlePicks();
            if (settled > 0) {
                models.invalidate();
            }
            refit();
            log.info("CLV job: {} closing lines stamped, {} bets settled, calibration {}", closed, settled,
                    String.format(Locale.US, "%+.3f", engine.calibration()));
        } catch (RuntimeException e) {
            log.warn("CLV job failed: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> pending() {
        return repo.jdbc().queryForList(
                "SELECT id, game_date, league, bookmaker, player_id, stat, side, line, odds FROM bet"
                        + " WHERE result='PENDING' AND player_id IS NOT NULL");
    }

    /** Stamps the latest seen price of each open bet whose game day has arrived; it freezes once the book pulls it. */
    public int captureClosing() {
        LocalDate today = AppClock.today();
        int n = 0;
        for (Map<String, Object> b : pending()) {
            LocalDate d = LocalDate.parse((String) b.get("game_date"));
            if (d.isAfter(today) || repo.excludedPlayer((String) b.get("league"), d, (String) b.get("player_id"))) {
                continue;
            }
            var latest = odds.latest((String) b.get("bookmaker"), d, (String) b.get("player_id"),
                    Stat.valueOf((String) b.get("stat")), ((Number) b.get("line")).doubleValue());
            if (latest.isEmpty()) {
                continue;
            }
            boolean over = "OVER".equals(b.get("side"));
            Double close = over ? latest.get().over() : latest.get().under();
            Double other = over ? latest.get().under() : latest.get().over();
            if (close == null) {
                continue;
            }
            double q = other == null ? 1.0 / close : (1.0 / close) / (1.0 / close + 1.0 / other);
            double clv = ((Number) b.get("odds")).doubleValue() * q - 1.0;
            repo.jdbc().update("UPDATE bet SET close_odds=?, clv=? WHERE id=?", close, clv, b.get("id"));
            n++;
        }
        return n;
    }

    /** Every model pick is logged once per game; the last odds seen before the book pulls the line is the close. */
    int logPicks(LocalDate date) {
        int n = 0;
        for (ValueEngine.ValueBet v : engine.top(engine.evaluate(date), 40)) {
            if (repo.excludedFixture(v.odds().league(), date, v.prediction().team(), v.prediction().opp())) {
                continue;
            }
            String side = v.side();
            Object[] key = {date.toString(), v.odds().playerId(), v.odds().stat().name(), v.line(), side,
                    v.odds().bookmaker()};
            double clv = v.bookOdds() * v.pFair() - 1.0;
            int updated = repo.jdbc().update("UPDATE pick_log SET close_odds=?, close_fair=?, clv=? WHERE game_date=?"
                    + " AND player_id=? AND stat=? AND line=? AND side=? AND bookmaker=? AND result IS NULL",
                    v.bookOdds(), v.pFair(), clv, key[0], key[1], key[2], key[3],
                    key[4], key[5]);
            if (updated == 0 && repo.jdbc().queryForObject("SELECT COUNT(*) FROM pick_log WHERE game_date=? AND"
                    + " player_id=? AND stat=? AND line=? AND side=? AND bookmaker=?", Integer.class, key) == 0) {
                repo.jdbc().update("INSERT INTO pick_log(game_date, league, player_id, player_name, stat, line, side,"
                        + " bookmaker, open_odds, close_odds, p_model, p_final, close_fair, clv) VALUES"
                        + " (?,?,?,?,?,?,?,?,?,?,?,?,?,0)", key[0], v.odds().league(), key[1], v.odds().playerName(),
                        key[2], key[3], key[4], key[5], v.bookOdds(), v.bookOdds(), v.pModel(), v.pFinal(), v.pFair());
                n++;
            }
        }
        return n;
    }

    /** Settles logged picks from stored box scores, and starts the matching ingest when finished games are missing. */
    public int settlePicks() {
        LocalDate today = AppClock.today();
        int n = 0;
        Set<IngestService.Source> need = new HashSet<>();
        for (Map<String, Object> p : repo.jdbc().queryForList("SELECT id, game_date, league, player_id, stat, line,"
                + " side, result FROM pick_log WHERE (result IS NULL OR result='VOID') AND game_date < ?",
                today.plusDays(1).toString())) {
            String league = (String) p.get("league"), date = (String) p.get("game_date");
            if (repo.excludedPlayer(league, LocalDate.parse(date), (String) p.get("player_id"))) {
                continue;
            }
            if (!teamCovered(league, date, (String) p.get("player_id"))) {
                if ("VOID".equals(p.get("result"))) {
                    repo.jdbc().update("UPDATE pick_log SET result=NULL, actual=NULL WHERE id=?", p.get("id"));
                }
                if (!LocalDate.parse(date).isAfter(today) && !ingest.status().running()) {
                    need.add(league.equals("NBA") ? IngestService.Source.NBA : league.equals("EL")
                            ? IngestService.Source.EUROLEAGUE : IngestService.Source.EUROCUP);
                }
                continue;
            }
            List<Double> actual = repo.jdbc().query("SELECT pts, reb, ast, fg3m, fg3a, fga, fta, stl, blk, tov FROM"
                            + " player_game WHERE player_id=? AND game_date=?",
                    (rs, i) -> Stat.valueOf((String) p.get("stat")).of(new double[]{rs.getDouble(1), rs.getDouble(2),
                            rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7),
                            rs.getDouble(8), rs.getDouble(9), rs.getDouble(10)}),
                    p.get("player_id"), date);
            double line = ((Number) p.get("line")).doubleValue();
            String res = "VOID";
            Double a = null;
            if (!actual.isEmpty()) {
                a = actual.get(0);
                res = a == line ? "PUSH" : ((a > line) == "OVER".equals(p.get("side"))) ? "WIN" : "LOSS";
            } else if ("VOID".equals(p.get("result"))) {
                continue;
            }
            repo.jdbc().update("UPDATE pick_log SET result=?, actual=? WHERE id=?", res, a, p.get("id"));
            n++;
        }
        need.forEach(ingest::start);
        return n;
    }

    /** VOID only when this player's team already has a box that night. League-wide boxes are not enough. */
    private boolean teamCovered(String league, String date, String playerId) {
        List<String> teams = repo.jdbc().query(
                "SELECT team FROM player_game WHERE player_id=? ORDER BY game_date DESC LIMIT 1",
                (rs, i) -> rs.getString(1), playerId);
        if (teams.isEmpty()) {
            return false;
        }
        Integer n = repo.jdbc().queryForObject(
                "SELECT COUNT(*) FROM team_game WHERE league=? AND game_date=? AND team=?", Integer.class, league,
                date, teams.get(0));
        return n != null && n > 0;
    }

    public Stats stats() {
        List<Map<String, Object>> rows = repo.jdbc().queryForList(
                "SELECT clv, result, p_model FROM pick_log WHERE close_odds IS NOT NULL AND"
                        + DataRepository.notExcludedSql("pick_log"));
        int clvN = 0, beat = 0, settled = 0, wins = 0;
        double clvSum = 0, probSum = 0, brier = 0;
        for (Map<String, Object> r : rows) {
            double c = ((Number) r.get("clv")).doubleValue();
            clvN++;
            clvSum += c;
            beat += c > 0 ? 1 : 0;
            String res = (String) r.get("result");
            if ("WIN".equals(res) || "LOSS".equals(res)) {
                double p = ((Number) r.get("p_model")).doubleValue();
                double hit = "WIN".equals(res) ? 1 : 0;
                settled++;
                wins += (int) hit;
                probSum += p;
                brier += (p - hit) * (p - hit);
            }
        }
        for (Map<String, Object> r : repo.jdbc().queryForList(
                "SELECT model_prob, result FROM bet WHERE result IN ('WIN','LOSS') AND model_prob IS NOT NULL AND"
                        + DataRepository.notExcludedSql("bet"))) {
            double p = ((Number) r.get("model_prob")).doubleValue();
            double hit = "WIN".equals(r.get("result")) ? 1 : 0;
            settled++;
            wins += (int) hit;
            probSum += p;
            brier += (p - hit) * (p - hit);
        }
        double hitRate = settled == 0 ? 0 : (double) wins / settled;
        double avgProb = settled == 0 ? 0 : probSum / settled;
        double shift = settled < MIN_SETTLED_FOR_CALIBRATION ? 0.0
                : Math.max(-MAX_SHIFT, Math.min(MAX_SHIFT, 0.5 * (hitRate - avgProb)));
        return new Stats(clvN, clvN == 0 ? 0 : clvSum / clvN, clvN == 0 ? 0 : (double) beat / clvN, settled, hitRate,
                avgProb, settled == 0 ? 0 : brier / settled, shift);
    }
}
