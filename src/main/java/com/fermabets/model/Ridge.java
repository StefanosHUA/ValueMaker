package com.fermabets.model;

import java.util.List;

/** Ridge regression on standardised features; the intercept is fitted separately. */
public final class Ridge {
    private final double[] beta;
    private final double[] mean;
    private final double[] std;
    private final double intercept;

    private Ridge(double intercept, double[] beta, double[] mean, double[] std) {
        this.intercept = intercept;
        this.beta = beta;
        this.mean = mean;
        this.std = std;
    }

    public static Ridge fit(List<double[]> x, double[] y, double lambda) {
        int n = x.size();
        int p = x.get(0).length;
        double[] mean = new double[p];
        double[] std = new double[p];
        for (double[] row : x) {
            for (int j = 0; j < p; j++) {
                mean[j] += row[j] / n;
            }
        }
        for (double[] row : x) {
            for (int j = 0; j < p; j++) {
                std[j] += (row[j] - mean[j]) * (row[j] - mean[j]) / n;
            }
        }
        for (int j = 0; j < p; j++) {
            std[j] = std[j] < 1e-12 ? 1.0 : Math.sqrt(std[j]);
        }
        double yMean = 0;
        for (double v : y) {
            yMean += v / n;
        }
        double[][] a = new double[p][p + 1];
        for (int i = 0; i < n; i++) {
            double[] z = new double[p];
            for (int j = 0; j < p; j++) {
                z[j] = (x.get(i)[j] - mean[j]) / std[j];
            }
            double yc = y[i] - yMean;
            for (int j = 0; j < p; j++) {
                for (int l = 0; l < p; l++) {
                    a[j][l] += z[j] * z[l];
                }
                a[j][p] += z[j] * yc;
            }
        }
        for (int j = 0; j < p; j++) {
            a[j][j] += lambda * n;
        }
        return new Ridge(yMean, solve(a, p), mean, std);
    }

    public double predict(double[] row) {
        double out = intercept;
        for (int j = 0; j < beta.length; j++) {
            out += beta[j] * (row[j] - mean[j]) / std[j];
        }
        return out;
    }

    /** Coefficient in original feature units (for the explanation panel). */
    public double rawCoefficient(int j) {
        return beta[j] / std[j];
    }

    private static double[] solve(double[][] a, int p) {
        for (int c = 0; c < p; c++) {
            int pivot = c;
            for (int r = c + 1; r < p; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[pivot][c])) {
                    pivot = r;
                }
            }
            double[] tmp = a[c];
            a[c] = a[pivot];
            a[pivot] = tmp;
            double d = a[c][c];
            if (Math.abs(d) < 1e-12) {
                continue;
            }
            for (int l = c; l <= p; l++) {
                a[c][l] /= d;
            }
            for (int r = 0; r < p; r++) {
                if (r != c) {
                    double f = a[r][c];
                    for (int l = c; l <= p; l++) {
                        a[r][l] -= f * a[c][l];
                    }
                }
            }
        }
        double[] out = new double[p];
        for (int j = 0; j < p; j++) {
            out[j] = a[j][p];
        }
        return out;
    }
}
