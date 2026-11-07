package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.domain.Stat;

import java.util.List;
import java.util.Locale;

/**
 * Consecutive games (most recent first) in which the player cleared tonight's line. DNP (0 minutes) is skipped.
 * Does not touch 55/45 weights.
 */
public final class LineStreak {
    public static final int MIN = 3;

    private LineStreak() {
    }

    /** Walks history from newest to oldest; history must already be before tonight and sorted by date. */
    public static int of(List<GameRow> history, Stat stat, double line, boolean over) {
        if (history == null || history.isEmpty() || stat == null) {
            return 0;
        }
        int n = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            GameRow r = history.get(i);
            if (r.minutes() <= 0) {
                continue;
            }
            double actual = stat.of(r);
            if (Double.isNaN(actual)) {
                break;
            }
            boolean hit = over ? actual > line : actual < line;
            if (!hit) {
                break;
            }
            n++;
        }
        return n;
    }

    public static boolean hot(int n) {
        return n >= MIN;
    }

    public static String label(int n, String side, double line) {
        if (!hot(n)) {
            return "—";
        }
        return String.format(Locale.US, "%d× %s %.1f", n, side, line);
    }
}
