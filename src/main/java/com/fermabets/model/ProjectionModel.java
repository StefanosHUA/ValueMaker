package com.fermabets.model;

import com.fermabets.domain.Stat;

import java.util.List;

/** Regression-projected mean plus an over-dispersed count distribution. */
public final class ProjectionModel {
    private final Stat stat;
    private final Ridge ridge;
    private final double dispersion;
    private final double rmse;
    private final int trainedOn;

    private ProjectionModel(Stat stat, Ridge ridge, double dispersion, double rmse, int trainedOn) {
        this.stat = stat;
        this.ridge = ridge;
        this.dispersion = dispersion;
        this.rmse = rmse;
        this.trainedOn = trainedOn;
    }

    public static ProjectionModel train(Stat stat, List<FeatureBuilder.Sample> samples) {
        if (samples.size() < 200) {
            throw new IllegalStateException("Not enough history to train " + stat + " (" + samples.size()
                    + " samples). Run the data ingestion first.");
        }
        List<double[]> x = samples.stream().map(FeatureBuilder.Sample::x).toList();
        double[] y = samples.stream().mapToDouble(FeatureBuilder.Sample::y).toArray();
        Ridge ridge = Ridge.fit(x, y, 0.01);
        double num = 0, den = 0, se = 0;
        for (int i = 0; i < y.length; i++) {
            double mu = Math.max(0.3, ridge.predict(x.get(i)));
            double res = y[i] - mu;
            se += res * res;
            num += (res * res - mu) * mu * mu;
            den += mu * mu * mu * mu;
        }
        double k = den > 0 ? Math.max(0.0, num / den) : 0.0;
        return new ProjectionModel(stat, ridge, k, Math.sqrt(se / y.length), y.length);
    }

    public double mean(double[] x) {
        return Math.max(0.05, ridge.predict(x));
    }

    public CountDist dist(double[] x) {
        return new CountDist(mean(x), dispersion);
    }

    public CountDist naive(double ewma) {
        return new CountDist(ewma, dispersion);
    }

    public double rmse() {
        return rmse;
    }

    public double dispersion() {
        return dispersion;
    }

    public int trainedOn() {
        return trainedOn;
    }

    public Stat stat() {
        return stat;
    }

    public double coefficient(int feature) {
        return ridge.rawCoefficient(feature);
    }
}
