package com.fermabets.model;

import com.fermabets.domain.GameRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LineupAdjusterTest {

    private static final String P = "NBA:p";
    private static final String T = "NBA:t";
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate TONIGHT = LocalDate.of(2026, 10, 20);

    @Test
    void outTeammateShiftsTowardHistoricalWithoutHim() {
        LeagueData data = league(withoutStar(3, 2), withStar(6, 10));
        var base = project(data, withStar(6, 10));
        var adj = LineupAdjuster.apply(base, data, P, "BOS", TONIGHT, START, Set.of(T), Set.of(),
                Map.of(T, "Star"));
        assertTrue(adj.projection().minutes() > base.minutes() + 3);
        assertTrue(adj.projection().fga() > base.fga() + 2);
        assertTrue(adj.notes().get(0).startsWith("Χωρίς Star"));
        assertEquals(base.games(), adj.projection().games());
    }

    @Test
    void returningTeammateShiftsTowardHistoricalWithHim() {
        LeagueData data = league(withStar(6, 2), withoutStar(3, 12));
        var base = project(data, data.history(P));
        var adj = LineupAdjuster.apply(base, data, P, "BOS", TONIGHT, START, Set.of(), Set.of(T),
                Map.of(T, "Star"));
        assertTrue(adj.projection().minutes() < base.minutes() - 2);
        assertTrue(adj.notes().get(0).startsWith("Επιστροφή Star"));
    }

    @Test
    void skipsWhenRecentGamesAlreadyMatchTonight() {
        LeagueData data = league(withStar(3, 2), withoutStar(6, 10));
        var base = project(data, data.history(P));
        var adj = LineupAdjuster.apply(base, data, P, "BOS", TONIGHT, START, Set.of(T), Set.of(),
                Map.of(T, "Star"));
        assertEquals(base.minutes(), adj.projection().minutes(), 0.01);
        assertTrue(adj.notes().isEmpty());
    }

    @Test
    void matchScoreIsOneWhenTeammateStateMatchesTonight() {
        LeagueData data = league(withoutStar(3, 2), withStar(6, 10));
        var conds = LineupAdjuster.conditions(data, P, "BOS", TONIGHT, START, Set.of(T), Set.of(),
                Map.of(T, "Star"));
        assertEquals(1, conds.size());
        assertTrue(conds.get(0).out());
        assertEquals(1.0, LineupAdjuster.matchScore(data, "BOS", "g2", conds), 1e-9);
        assertEquals(0.0, LineupAdjuster.matchScore(data, "BOS", "g10", conds), 1e-9);
    }

    @Test
    void rosterShareGrowsAsMoreMatchingGamesArrive() {
        assertEquals(0.0, LineupAdjuster.blendShare(2), 1e-9);
        assertTrue(LineupAdjuster.blendShare(3) > 0);
        assertTrue(LineupAdjuster.blendShare(15) > LineupAdjuster.blendShare(3));
        assertTrue(LineupAdjuster.blendShare(15) < 0.40);
    }

    @Test
    void needsThreeGamesEachSide() {
        LeagueData data = league(withoutStar(2, 2), withStar(6, 10));
        var base = project(data, withStar(6, 10));
        var adj = LineupAdjuster.apply(base, data, P, "BOS", TONIGHT, START, Set.of(T), Set.of(),
                Map.of(T, "Star"));
        assertTrue(adj.notes().isEmpty());
        assertEquals(base.minutes(), adj.projection().minutes(), 0.01);
    }

    private static List<GameRow> withStar(int n, int startDay) {
        List<GameRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int day = startDay + i;
            rows.add(player("g" + day, P, day, 28, 10, 2, 2));
            rows.add(player("g" + day, T, day, 32, 12, 4, 2));
        }
        return rows;
    }

    private static List<GameRow> withoutStar(int n, int startDay) {
        List<GameRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int day = startDay + i;
            rows.add(player("g" + day, P, day, 36, 16, 5, 3));
        }
        return rows;
    }

    private static GameRow player(String gid, String id, int day, double min, double fga, double fta, double tov) {
        double[] v = new double[GameRow.N_STATS];
        v[GameRow.FGA] = fga;
        v[GameRow.FTA] = fta;
        v[GameRow.TOV] = tov;
        v[GameRow.FG3A] = fga * 0.4;
        return new GameRow(gid, id, "NBA", LocalDate.of(2026, 10, day), "BOS", "NYK", true, min, v);
    }

    @SafeVarargs
    private static LeagueData league(List<GameRow>... groups) {
        List<GameRow> all = new ArrayList<>();
        for (List<GameRow> g : groups) {
            all.addAll(g);
        }
        return new LeagueData("NBA", all, List.of(), Map.of());
    }

    private static ProjectionBuilder.Projection project(LeagueData data, List<GameRow> pRows) {
        List<GameRow> mine = pRows.stream().filter(r -> r.playerId().equals(P)).toList();
        double[] usg = mine.stream().mapToDouble(data::usage).toArray();
        return ProjectionBuilder.build(mine, usg, 100, 102);
    }
}
