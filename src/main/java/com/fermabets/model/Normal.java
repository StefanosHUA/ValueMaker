package com.fermabets.model;

/** Standard normal helpers. */
public final class Normal {
    private Normal() {
    }

    public static double cdf(double z) {
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    /** Inverse CDF by bisection (precision is plenty for betting-odds work). */
    public static double inv(double p) {
        double q = Math.min(0.999, Math.max(0.001, p));
        double lo = -5, hi = 5;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (cdf(mid) < q) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    private static double erf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592)
                * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }
}
