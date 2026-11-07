package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.domain.Stat;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineStreakTest {

    @Test
    void threeStraightOversCountAsHot() {
        var h = games(20, 18, 22);
        assertEquals(3, LineStreak.of(h, Stat.PTS, 14.5, true));
        assertTrue(LineStreak.hot(3));
        assertEquals("3× OVER 14.5", LineStreak.label(3, "OVER", 14.5));
    }

    @Test
    void missBreaksTheStreak() {
        var h = games(20, 10, 22);
        assertEquals(1, LineStreak.of(h, Stat.PTS, 14.5, true));
        assertEquals("—", LineStreak.label(1, "OVER", 14.5));
    }

    @Test
    void dnpIsSkippedNotAMiss() {
        List<GameRow> h = new ArrayList<>();
        h.add(pts(1, 20, 16));
        h.add(pts(2, 0, 0));
        h.add(pts(3, 22, 18));
        h.add(pts(4, 24, 19));
        assertEquals(3, LineStreak.of(h, Stat.PTS, 14.5, true));
    }

    @Test
    void underStreakUsesTheOtherSide() {
        var h = games(8, 9, 7);
        assertEquals(3, LineStreak.of(h, Stat.PTS, 14.5, false));
        assertEquals(0, LineStreak.of(h, Stat.PTS, 14.5, true));
    }

    private static List<GameRow> games(double... pts) {
        List<GameRow> out = new ArrayList<>();
        for (int i = 0; i < pts.length; i++) {
            out.add(pts(i + 1, 30, pts[i]));
        }
        return out;
    }

    private static GameRow pts(int day, double min, double points) {
        double[] v = new double[GameRow.N_STATS];
        v[GameRow.PTS] = points;
        return new GameRow("g" + day, "NBA:p", "NBA", LocalDate.of(2026, 10, day), "BOS", "NYK", true, min, v);
    }
}
