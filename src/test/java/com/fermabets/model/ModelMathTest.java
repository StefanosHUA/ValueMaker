package com.fermabets.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ModelMathTest {

    @Test
    void poissonCdfMatchesKnownValue() {
        CountDist d = new CountDist(4.0, 0.0);
        assertEquals(0.4335, d.cdf(3), 1e-3);
        assertEquals(1.0, d.cdf(3) + d.over(3.5), 1e-9);
    }

    @Test
    void negativeBinomialHasMoreSpreadThanPoisson() {
        CountDist poisson = new CountDist(20, 0.0);
        CountDist nb = new CountDist(20, 0.1);
        assertTrue(nb.over(30.5) > poisson.over(30.5));
        assertTrue(nb.under(10.5) > poisson.under(10.5));
    }

    @Test
    void wholeLineHasPushMass() {
        CountDist d = new CountDist(5, 0.0);
        assertEquals(1.0, d.over(5) + d.under(5) + d.push(5), 1e-9);
        assertTrue(d.push(5) > 0);
        assertEquals(0.0, d.push(5.5));
    }

    @Test
    void ridgeRecoversLinearRelationship() {
        Random rnd = new Random(7);
        List<double[]> x = new ArrayList<>();
        double[] y = new double[500];
        for (int i = 0; i < 500; i++) {
            double a = rnd.nextGaussian() * 3, b = rnd.nextGaussian();
            x.add(new double[]{a, b});
            y[i] = 2.0 * a - 1.5 * b + 10 + rnd.nextGaussian() * 0.1;
        }
        Ridge r = Ridge.fit(x, y, 1e-6);
        assertEquals(2.0, r.rawCoefficient(0), 0.05);
        assertEquals(-1.5, r.rawCoefficient(1), 0.05);
        assertEquals(10 + 2.0 * 1 - 1.5 * 2, r.predict(new double[]{1, 2}), 0.1);
    }
}
