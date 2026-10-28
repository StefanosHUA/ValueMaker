package com.fermabets.model;

/** Count distribution for stat outcomes: Poisson when k ~ 0, negative binomial (var = mu + k*mu^2) otherwise. */
public final class CountDist {
    private final double mu;
    private final double k;

    public CountDist(double mu, double k) {
        this.mu = Math.max(mu, 0.02);
        this.k = Math.max(k, 0.0);
    }

    public double mean() {
        return mu;
    }

    /** P(X <= n). */
    public double cdf(int n) {
        if (n < 0) {
            return 0.0;
        }
        double sum = 0, p = pmf0();
        for (int i = 0; i <= n; i++) {
            sum += p;
            p = next(p, i);
            if (sum >= 1.0) {
                return 1.0;
            }
        }
        return Math.min(sum, 1.0);
    }

    public double pmf(int n) {
        if (n < 0) {
            return 0.0;
        }
        double p = pmf0();
        for (int i = 0; i < n; i++) {
            p = next(p, i);
        }
        return p;
    }

    /** Probability of strictly more than the line (half lines) or more with pushes excluded (whole lines). */
    public double over(double line) {
        return 1.0 - cdf((int) Math.floor(line));
    }

    public double under(double line) {
        return cdf((int) Math.ceil(line) - 1);
    }

    public double push(double line) {
        return line == Math.rint(line) ? pmf((int) line) : 0.0;
    }

    private double pmf0() {
        if (k < 1e-6) {
            return Math.exp(-mu);
        }
        double r = 1.0 / k;
        return Math.exp(r * Math.log(r / (r + mu)));
    }

    private double next(double p, int i) {
        if (k < 1e-6) {
            return p * mu / (i + 1);
        }
        double r = 1.0 / k;
        double q = mu / (r + mu);
        return p * (i + r) / (i + 1) * q;
    }
}
