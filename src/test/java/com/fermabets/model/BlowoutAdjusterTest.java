package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.domain.TeamGame;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlowoutAdjusterTest {

    private static final String P = "NBA:p";
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate TONIGHT = LocalDate.of(2026, 10, 20);

    @Test
    void favouriteBlowoutPullsMinutesDownTowardThoseGames() {
        var data = league(blowouts(4, 2, 24, 8, 120, 100), close(4, 10, 34, 14, 105, 103));
        var base = project(data);
        Map<String, Double> exp = expected(4, 2, 18, 4, 10, 2);
        var adj = BlowoutAdjuster.apply(base, data, P, "BOS", TONIGHT, START, -9.5, exp, Map.of());
        assertTrue(adj.projection().minutes() < base.minutes() - 3, adj.notes().toString());
        assertTrue(adj.projection().fga() < base.fga() - 2);
        assertTrue(adj.notes().get(0).contains("φαβορί"));
        assertTrue(adj.notes().get(0).contains("αναμενόμενο + τελικό"));
    }

    @Test
    void closeSpreadDoesNotShift() {
        var data = league(blowouts(4, 2, 24, 8, 120, 100), close(4, 10, 34, 14, 105, 103));
        var base = project(data);
        var adj = BlowoutAdjuster.apply(base, data, P, "BOS", TONIGHT, START, -7.0, Map.of(), Map.of());
        assertEquals(base.minutes(), adj.projection().minutes(), 0.01);
        assertTrue(adj.notes().isEmpty());
    }

    @Test
    void preferredMatchScoreIsOneOnlyWhenExpectedAndActualAlign() {
        var data = league(blowouts(3, 2, 24, 8, 120, 100), close(3, 10, 34, 14, 105, 103));
        Map<String, Double> exp = expected(3, 2, 18, 3, 10, 2);
        GameRow blow = data.history(P).get(0);
        GameRow calm = data.history(P).get(5);
        assertEquals(1.0, BlowoutAdjuster.matchScore(data, blow, -10, exp, Map.of()), 1e-9);
        assertEquals(0.0, BlowoutAdjuster.matchScore(data, calm, -10, exp, Map.of()), 1e-9);
    }

    @Test
    void actualScoreBlowoutsStillShiftWhenBooksWereMissing() {
        var data = league(blowouts(4, 2, 24, 8, 120, 100), close(4, 10, 34, 14, 105, 103));
        var base = project(data);
        Map<String, Double> noBook = expected(4, 2, 2, 4, 10, 2);
        var adj = BlowoutAdjuster.apply(base, data, P, "BOS", TONIGHT, START, -9.5, noBook, Map.of());
        assertTrue(adj.projection().minutes() < base.minutes() - 3, adj.notes().toString());
        assertTrue(adj.notes().get(0).contains("τελικό σκορ blowout"));
    }

    @Test
    void fewHistoricalBlowoutsLeaveProjectionAndExplain() {
        var data = league(blowouts(1, 2, 24, 8, 120, 100), close(5, 10, 34, 14, 105, 103));
        var base = project(data);
        Map<String, Double> exp = expected(1, 2, 18, 5, 10, 2);
        var adj = BlowoutAdjuster.apply(base, data, P, "BOS", TONIGHT, START, -9.5, exp, Map.of());
        assertEquals(base.minutes(), adj.projection().minutes(), 0.01);
        assertTrue(adj.notes().get(0).contains("λίγα ιστορικά"));
    }

    private static List<GameRow> blowouts(int n, int startDay, double min, double fga, double us, double them) {
        return games(n, startDay, min, fga, us, them);
    }

    private static List<GameRow> close(int n, int startDay, double min, double fga, double us, double them) {
        return games(n, startDay, min, fga, us, them);
    }

    private static List<GameRow> games(int n, int startDay, double min, double fga, double us, double them) {
        List<GameRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int day = startDay + i;
            String gid = "g" + day;
            double[] v = new double[GameRow.N_STATS];
            v[GameRow.FGA] = fga;
            v[GameRow.FTA] = 3;
            v[GameRow.FG3A] = fga * 0.4;
            v[GameRow.PTS] = fga;
            rows.add(new GameRow(gid, P, "NBA", LocalDate.of(2026, 10, day), "BOS", "NYK", true, min, v));
            rows.add(new GameRow(gid, "NBA:x", "NBA", LocalDate.of(2026, 10, day), "NYK", "BOS", false, 20, v.clone()));
        }
        return rows;
    }

    private static List<TeamGame> boxes(List<GameRow> rows, double us, double them) {
        List<TeamGame> out = new ArrayList<>();
        for (GameRow r : rows) {
            if (!r.playerId().equals(P)) {
                continue;
            }
            out.add(new TeamGame(r.gameId(), "BOS", "NYK", r.date(), true, us, 100, 20, 20));
            out.add(new TeamGame(r.gameId(), "NYK", "BOS", r.date(), false, them, 100, 20, 20));
        }
        return out;
    }

    @SafeVarargs
    private static LeagueData league(List<GameRow>... groups) {
        List<GameRow> all = new ArrayList<>();
        List<TeamGame> boxes = new ArrayList<>();
        boolean first = true;
        for (List<GameRow> g : groups) {
            all.addAll(g);
            boxes.addAll(boxes(g, first ? 120 : 105, first ? 100 : 103));
            first = false;
        }
        return new LeagueData("NBA", all, boxes, Map.of());
    }

    private static Map<String, Double> expected(int nBlow, int blowStart, double blowExp, int nClose, int closeStart,
                                                double closeExp) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < nBlow; i++) {
            m.put("g" + (blowStart + i), blowExp);
        }
        for (int i = 0; i < nClose; i++) {
            m.put("g" + (closeStart + i), closeExp);
        }
        return m;
    }

    private static ProjectionBuilder.Projection project(LeagueData data) {
        List<GameRow> mine = data.history(P);
        double[] usg = mine.stream().mapToDouble(data::usage).toArray();
        return ProjectionBuilder.build(mine, usg, 100, 102);
    }
}
