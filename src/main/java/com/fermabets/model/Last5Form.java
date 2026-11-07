package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.domain.Stat;

import java.util.List;

/**
 * Combines the season projection with the last-5 projection. {@link #fitWeight} learns the last-5 share
 * from history (least squares, clipped to 0..1). 55/45 weights are not touched.
 */
public final class Last5Form {
    public static final int GAMES = 5;
    public static final double BLEND = 0.40;
    public static final Stat[] FIT_STATS = {Stat.PTS, Stat.REB, Stat.AST, Stat.FG3M};

    private Last5Form() {
    }

    public static double mix(int n) {
        return mix(n, BLEND);
    }

    public static double mix(int n, double weight) {
        return n > GAMES ? weight : 0.0;
    }

    public static double blend(double season, double last5, double mix) {
        return (1.0 - mix) * season + mix * last5;
    }

    /** Scale so PTS/REB/AST/3s can be fitted together without points dominating. */
    public static double scale(Stat stat) {
        return switch (stat) {
            case PTS, PRA -> 8.0;
            case REB, PR, PA, RA -> 3.0;
            case AST -> 2.5;
            default -> 1.5;
        };
    }

    /** acc is {xx, xy} for (last5 - season) vs (actual - season). */
    public static void acc(double[] acc, double season, double last5, double y, double scale) {
        double s = Math.max(scale, 1e-3);
        double dx = (last5 - season) / s;
        if (Math.abs(dx) < 1e-9) {
            return;
        }
        acc[0] += dx * dx;
        acc[1] += dx * ((y - season) / s);
    }

    public static double fitWeight(double xx, double xy) {
        if (xx < 1e-9) {
            return BLEND;
        }
        return Math.min(1.0, Math.max(0.0, xy / xx));
    }

    /** Per-minute rate of the stat, mixed the same way as FGA/FTA/3PA, then scaled to projected minutes. */
    public static double projectedStat(List<GameRow> season, Stat stat, double minutes, double mix) {
        int n = season.size();
        if (n == 0 || minutes <= 0) {
            return 0;
        }
        double totMin = 0, tot = 0, l5Min = 0, l5 = 0;
        int k = Math.min(GAMES, n);
        for (int i = 0; i < n; i++) {
            GameRow r = season.get(i);
            totMin += r.minutes();
            tot += stat.of(r);
            if (i >= n - k) {
                l5Min += r.minutes();
                l5 += stat.of(r);
            }
        }
        double seasonRate = totMin > 0 ? tot / totMin : 0;
        double last5Rate = l5Min > 0 ? l5 / l5Min : 0;
        return blend(seasonRate, last5Rate, mix) * minutes;
    }
}
