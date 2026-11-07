package com.fermabets.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValueEngineShrinkTest {

    @Test
    void youngSeasonPullsExtremePTowardHalf() {
        assertEquals(0.5 + (3.0 / 8.0) * 0.49, ValueEngine.shrinkYoungSeason(0.99, 3), 1e-9);
        assertEquals(0.99, ValueEngine.shrinkYoungSeason(0.99, 8), 1e-9);
        assertEquals(0.5, ValueEngine.shrinkYoungSeason(0.5, 3), 1e-9);
    }

    @Test
    void underProbabilityIsComplementOfOver() {
        assertEquals(0.37, ValueEngine.pForSide(0.63, "UNDER"), 1e-9);
        assertEquals(0.63, ValueEngine.pForSide(0.63, "OVER"), 1e-9);
        assertEquals(0.63, ValueEngine.pForSide(0.63, null), 1e-9);
        assertEquals(0.50, ValueEngine.pForSide(0.50, "under"), 1e-9);
    }
}
