package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.domain.TeamGame;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackToBackAdjusterTest {

    private static final String P = "NBA:p";
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate TONIGHT = LocalDate.of(2026, 10, 20);

    @Test
    void backToBackPullsMinutesTowardThoseNights() {
        var data = league();
        var base = project(data);
        var adj = BackToBackAdjuster.apply(base, data, P, "BOS", TONIGHT, START);
        assertTrue(adj.projection().minutes() < base.minutes() - 3, adj.notes().toString());
        assertTrue(adj.projection().fga() < base.fga() - 2);
        assertTrue(adj.notes().get(0).contains("Back-to-back"));
        assertTrue(adj.notes().get(0).contains("έπαιξε χθες"));
    }

    @Test
    void restedNightDoesNotShift() {
        var data = league();
        var base = project(data);
        LocalDate rested = LocalDate.of(2026, 10, 18);
        var adj = BackToBackAdjuster.apply(base, data, P, "BOS", rested, START);
        assertEquals(base.minutes(), adj.projection().minutes(), 0.01);
        assertTrue(adj.notes().isEmpty());
    }

    @Test
    void matchScoreIsOneOnlyOnHistoricalB2bWhenTonightIsB2b() {
        var data = league();
        List<GameRow> h = data.history(P);
        GameRow b2b = h.stream().filter(r -> r.date().equals(LocalDate.of(2026, 10, 3))).findFirst().orElseThrow();
        GameRow rest = h.stream().filter(r -> r.date().equals(LocalDate.of(2026, 10, 2))).findFirst().orElseThrow();
        assertEquals(1.0, BackToBackAdjuster.matchScore(h, b2b, TONIGHT), 1e-9);
        assertEquals(0.0, BackToBackAdjuster.matchScore(h, rest, TONIGHT), 1e-9);
        assertEquals(0.0, BackToBackAdjuster.matchScore(h, b2b, LocalDate.of(2026, 10, 18)), 1e-9);
    }

    @Test
    void fewHistoricalB2bLeaveProjectionAndExplain() {
        List<GameRow> rows = new ArrayList<>();
        rows.addAll(game(2, 34, 14));
        rows.addAll(game(3, 24, 8));
        rows.addAll(game(19, 30, 12));
        var data = new LeagueData("NBA", rows, boxes(rows), Map.of());
        var base = project(data);
        var adj = BackToBackAdjuster.apply(base, data, P, "BOS", TONIGHT, START);
        assertEquals(base.minutes(), adj.projection().minutes(), 0.01);
        assertTrue(adj.notes().get(0).contains("λίγα ιστορικά"));
    }

    private static LeagueData league() {
        List<GameRow> rows = new ArrayList<>();
        rows.addAll(game(2, 34, 14));
        rows.addAll(game(3, 24, 8));
        rows.addAll(game(5, 34, 14));
        rows.addAll(game(6, 24, 8));
        rows.addAll(game(8, 34, 14));
        rows.addAll(game(9, 24, 8));
        rows.addAll(game(11, 34, 14));
        rows.addAll(game(12, 24, 8));
        rows.addAll(game(19, 30, 12));
        return new LeagueData("NBA", rows, boxes(rows), Map.of());
    }

    private static List<GameRow> game(int day, double min, double fga) {
        double[] v = new double[GameRow.N_STATS];
        v[GameRow.FGA] = fga;
        v[GameRow.FTA] = 3;
        v[GameRow.FG3A] = fga * 0.4;
        v[GameRow.PTS] = fga;
        String gid = "g" + day;
        LocalDate d = LocalDate.of(2026, 10, day);
        return List.of(new GameRow(gid, P, "NBA", d, "BOS", "NYK", true, min, v),
                new GameRow(gid, "NBA:x", "NBA", d, "NYK", "BOS", false, 20, v.clone()));
    }

    private static List<TeamGame> boxes(List<GameRow> rows) {
        List<TeamGame> out = new ArrayList<>();
        for (GameRow r : rows) {
            if (!P.equals(r.playerId())) {
                continue;
            }
            out.add(new TeamGame(r.gameId(), "BOS", "NYK", r.date(), true, 110, 100, 20, 20));
            out.add(new TeamGame(r.gameId(), "NYK", "BOS", r.date(), false, 105, 100, 20, 20));
        }
        return out;
    }

    private static ProjectionBuilder.Projection project(LeagueData data) {
        List<GameRow> mine = data.history(P);
        double[] usg = mine.stream().mapToDouble(data::usage).toArray();
        return ProjectionBuilder.build(mine, usg, 100, 102);
    }
}
