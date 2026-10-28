package com.fermabets.model;

import com.fermabets.domain.GameRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionBuilderTest {

    private static GameRow row(int day, double min, double fga, double fta, double fg3a) {
        double[] v = new double[GameRow.N_STATS];
        v[GameRow.FGA] = fga;
        v[GameRow.FTA] = fta;
        v[GameRow.FG3A] = fg3a;
        return new GameRow("g" + day, "p", "NBA", LocalDate.of(2026, 10, day), "BOS", "NYK", true, min, v);
    }

    @Test
    void fewerThanThreeCurrentSeasonGamesYieldsNoBands() {
        List<GameRow> rows = List.of(row(22, 30, 12, 4, 5), row(24, 28, 11, 3, 4));
        var p = ProjectionBuilder.build(rows, new double[]{24, 23}, 100, 102);
        assertEquals(2, p.games());
        assertFalse(p.ready());
        assertNull(p.minsBand());
    }

    @Test
    void threeGamesBuildBandsAroundCurrentSeasonRates() {
        List<GameRow> rows = new ArrayList<>();
        double[] usg = new double[3];
        for (int i = 0; i < 3; i++) {
            rows.add(row(22 + i, 30, 15, 4, 6));
            usg[i] = 25;
        }
        var p = ProjectionBuilder.build(rows, usg, 100, 104);
        assertTrue(p.ready());
        assertEquals(3, p.games());
        assertEquals(30.0, p.minutes(), 0.01);
        assertEquals(25.0, p.usage(), 0.01);
        assertEquals(15.0, p.fga(), 0.01);
        assertEquals(102.0, p.pace(), 0.01);
        assertEquals(28.0, p.minsBand().low(), 0.01);
        assertEquals(32.0, p.minsBand().high(), 0.01);
        assertEquals(22.5, p.usgBand().low(), 0.01);
        assertEquals(27.5, p.usgBand().high(), 0.01);
        assertEquals(3.0, p.ftaBand().low(), 0.01);
        assertEquals(5.0, p.ftaBand().high(), 0.01);
        assertEquals(5.0, p.fg3aBand().low(), 0.01);
        assertEquals(7.0, p.fg3aBand().high(), 0.01);
        assertEquals(100.0, p.paceBand().low(), 0.01);
        assertEquals(104.0, p.paceBand().high(), 0.01);
        assertEquals(12.8, p.fgaBand().low(), 0.01);
        assertEquals(17.3, p.fgaBand().high(), 0.01);
    }
}
