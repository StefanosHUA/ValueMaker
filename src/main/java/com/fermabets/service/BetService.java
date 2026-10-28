package com.fermabets.service;

import com.fermabets.domain.Stat;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@Service
public class BetService {
    public record Bet(long id, LocalDate date, String league, String bookmaker, String player, String playerId,
                      Stat stat, String side, double line, double odds, double stake, Double modelProb,
                      String result, Double actual, Double profit, Double clv) {
    }

    public record Summary(int settled, int pending, int wins, double staked, double profit, double roi,
                          double monthProfit, double monthTarget) {
    }

    private static final double MONTH_TARGET = 100.0;

    private final DataRepository repo;

    public BetService(DataRepository repo) {
        this.repo = repo;
    }

    public void place(LocalDate date, String league, String bookmaker, String player, String playerId, Stat stat,
                      String side, double line, double odds, double stake, double prob) {
        repo.jdbc().update("INSERT INTO bet(placed_at, game_date, league, bookmaker, player_name, player_id, stat,"
                        + " side, line, odds, stake, model_prob) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                Instant.now().toString(), date.toString(), league, bookmaker, player, playerId, stat.name(), side,
                line, odds, stake, prob);
    }

    public List<Bet> all() {
        return repo.jdbc().query("SELECT id, game_date, league, bookmaker, player_name, player_id, stat, side, line,"
                        + " odds, stake, model_prob, result, actual, profit, clv FROM bet ORDER BY game_date DESC, id DESC",
                (rs, i) -> new Bet(rs.getLong(1), LocalDate.parse(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), Stat.valueOf(rs.getString(7)),
                        rs.getString(8), rs.getDouble(9), rs.getDouble(10), rs.getDouble(11),
                        rs.getObject(12) == null ? null : rs.getDouble(12), rs.getString(13),
                        rs.getObject(14) == null ? null : rs.getDouble(14),
                        rs.getObject(15) == null ? null : rs.getDouble(15),
                        rs.getObject(16) == null ? null : rs.getDouble(16)));
    }

    public void delete(long id) {
        repo.jdbc().update("DELETE FROM bet WHERE id=?", id);
    }

    /** Settles pending bets from ingested box scores. Returns how many were settled. */
    public int settlePending() {
        int n = 0;
        for (Bet b : all()) {
            if (!b.result().equals("PENDING") || b.playerId() == null) {
                continue;
            }
            List<double[]> rows = repo.jdbc().query(
                    "SELECT pts, reb, ast, fg3m, fg3a, fga, fta, stl, blk, tov FROM player_game"
                            + " WHERE player_id=? AND game_date=?",
                    (rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                            rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9),
                            rs.getDouble(10)},
                    b.playerId(), b.date().toString());
            if (rows.isEmpty()) {
                boolean teamPlayed = hasGameData(b);
                if (teamPlayed) {
                    update(b.id(), "VOID", null, 0.0);
                    n++;
                }
                continue;
            }
            double actual = b.stat().of(rows.get(0));
            n += settle(b, actual) ? 1 : 0;
        }
        return n;
    }

    /** Player has no row but the league data already covers that date, so he did not play: the bet is void. */
    private boolean hasGameData(Bet b) {
        Integer c = repo.jdbc().queryForObject(
                "SELECT COUNT(*) FROM player_game WHERE league=? AND game_date=?", Integer.class, b.league(),
                b.date().toString());
        return c != null && c > 0;
    }

    private boolean settle(Bet b, double actual) {
        boolean over = b.side().equals("OVER");
        String result;
        double profit;
        if (actual == b.line()) {
            result = "PUSH";
            profit = 0;
        } else if ((actual > b.line()) == over) {
            result = "WIN";
            profit = b.stake() * (b.odds() - 1);
        } else {
            result = "LOSS";
            profit = -b.stake();
        }
        update(b.id(), result, actual, Math.round(profit * 100) / 100.0);
        return true;
    }

    private void update(long id, String result, Double actual, double profit) {
        repo.jdbc().update("UPDATE bet SET result=?, actual=?, profit=? WHERE id=?", result, actual, profit, id);
    }

    public Summary summary() {
        int settled = 0, pending = 0, wins = 0;
        double staked = 0, profit = 0, month = 0;
        YearMonth now = YearMonth.now();
        for (Bet b : all()) {
            if (b.result().equals("PENDING")) {
                pending++;
                continue;
            }
            if (b.result().equals("VOID")) {
                continue;
            }
            settled++;
            staked += b.stake();
            profit += b.profit() == null ? 0 : b.profit();
            wins += b.result().equals("WIN") ? 1 : 0;
            if (YearMonth.from(b.date()).equals(now)) {
                month += b.profit() == null ? 0 : b.profit();
            }
        }
        return new Summary(settled, pending, wins, staked, profit, staked == 0 ? 0 : profit / staked, month,
                MONTH_TARGET);
    }
}
