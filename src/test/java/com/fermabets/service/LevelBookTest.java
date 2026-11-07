package com.fermabets.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LevelBookTest {

    @Test
    void probabilityAndOddsLandInExpectedBands() {
        assertEquals(0, LevelBook.band(0.12, LevelBook.P_EDGES));
        assertEquals(2, LevelBook.band(0.35, LevelBook.P_EDGES));
        assertEquals(5, LevelBook.band(0.65, LevelBook.P_EDGES));
        assertEquals(6, LevelBook.band(0.99, LevelBook.P_EDGES));
        assertEquals(1, LevelBook.band(1.70, LevelBook.O_EDGES));
        assertEquals(4, LevelBook.band(4.20, LevelBook.O_EDGES));
    }

    @Test
    void trustNeedsSamplesAndFlagsOverconfidence() {
        assertEquals("λίγα δείγματα", LevelBook.trustOf(new LevelBook.Cell(3, 2, 1.8)));
        assertEquals("εμπιστεύσου", LevelBook.trustOf(new LevelBook.Cell(10, 6, 5.8)));
        assertEquals("υπερεκτιμά", LevelBook.trustOf(new LevelBook.Cell(10, 4, 7.0)));
    }

    @Test
    void labelsAreInclusiveRanges() {
        assertEquals("50–60%", LevelBook.pLabel(0.50, 0.60));
        assertEquals("70–100%", LevelBook.pLabel(0.70, 1.01));
        assertEquals("1.80–2.20", LevelBook.oLabel(1.80, 2.20));
        assertEquals("3.00+", LevelBook.oLabel(3.00, 99));
    }

    @Test
    void comboScorePrefersProfitAndDropsOverconfidentCells() {
        var good = new LevelBook.Combo("50–60%", "1.80–2.20", 0.5, 0.6, 1.8, 2.2,
                new LevelBook.Cell(10, 6, 5.5), 2.4);
        var thin = new LevelBook.Combo("70–100%", "3.00+", 0.7, 1.01, 3.0, 99,
                new LevelBook.Cell(2, 2, 1.8), 4.0);
        var loud = new LevelBook.Combo("70–100%", "1.50–1.80", 0.7, 1.01, 1.5, 1.8,
                new LevelBook.Cell(10, 4, 8.0), -2.0);
        assertTrue(good.score() > loud.score());
        assertEquals(Double.NEGATIVE_INFINITY, thin.score());
        assertEquals("50–60%", good.pLabel());
    }
}
