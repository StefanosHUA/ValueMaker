package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.domain.Stat;

import java.util.List;

/**
 * Port of the legacy credibility-weighted hit-rate model (Buhlmann Z = n / (n + K)) with filters
 * derived from the database instead of typed by hand.
 */
public final class BaselineModel {
    private static final double K = 5.0;
    private static final double K_H2H = 2.0;
    private static final int WINDOW = 80;

    private BaselineModel() {
    }

    public record Probs(double over, double under) {
    }

    /** Returns null when the history is empty. */
    public static Probs probs(List<GameRow> history, Stat stat, double line, boolean home, String opp,
                              double projMinutes) {
        List<GameRow> recent = history.subList(Math.max(0, history.size() - WINDOW), history.size());
        if (recent.isEmpty()) {
            return null;
        }
        double[] weights = {0.25, 0.35, 0.20, 0.20};
        double[] kk = {K, K, K, K_H2H};
        double num = 0, numU = 0, den = 0;
        for (int f = 0; f < 4; f++) {
            int n = 0, over = 0, under = 0;
            for (GameRow r : recent) {
                boolean keep = switch (f) {
                    case 0 -> true;
                    case 1 -> Math.abs(r.minutes() - projMinutes) <= Math.max(4.0, projMinutes * 0.2);
                    case 2 -> r.home() == home;
                    default -> r.opp().equals(opp);
                };
                if (!keep) {
                    continue;
                }
                n++;
                double v = stat.of(r);
                if (v > line) {
                    over++;
                } else if (v < line) {
                    under++;
                }
            }
            if (n == 0) {
                continue;
            }
            double z = n / (n + kk[f]);
            double w = weights[f] * z;
            num += w * over / n;
            numU += w * under / n;
            den += w;
        }
        return den == 0 ? null : new Probs(num / den, numU / den);
    }
}
