package com.fermabets.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Last5FormTest {

    @Test
    void fitWeightRecoversTheBlendWhenComboIsTheTruth() {
        double[] acc = new double[2];
        for (int i = 0; i < 20; i++) {
            double season = 20 + i * 0.1;
            double last5 = 12 + i * 0.1;
            double y = Last5Form.blend(season, last5, 0.3);
            Last5Form.acc(acc, season, last5, y, 8);
        }
        assertEquals(0.3, Last5Form.fitWeight(acc[0], acc[1]), 0.01);
    }

    @Test
    void fitWeightFallsBackWhenSeasonAndLast5Match() {
        double[] acc = new double[2];
        Last5Form.acc(acc, 18, 18, 20, 8);
        assertEquals(Last5Form.BLEND, Last5Form.fitWeight(acc[0], acc[1]), 1e-9);
    }

    @Test
    void mixIsZeroUntilMoreThanFiveGames() {
        assertEquals(0.0, Last5Form.mix(5, 0.7), 1e-9);
        assertEquals(0.7, Last5Form.mix(6, 0.7), 1e-9);
    }
}
