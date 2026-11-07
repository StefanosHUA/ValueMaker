package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.model.ProjectionBuilder.Projection;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * When the player played yesterday, shift minutes/usage/rates toward his own back-to-back nights versus rest
 * nights. Hit rates can use the same split. 55/45 weights stay the same.
 */
public final class BackToBackAdjuster {
    public static final int MIN_SPLIT = 3;
    public static final double MATCH_BOOST = 1.5;
    static final double CAP_MINS = 8.0;
    static final double CAP_USG = 6.0;
    static final double CAP_FGA = 5.0;
    static final double CAP_FTA = 3.0;
    static final double CAP_FG3A = 3.0;

    private BackToBackAdjuster() {
    }

    public record Adjustment(Projection projection, List<String> notes) {
    }

    /** True when this player had minutes on the calendar day before {@code date}. */
    public static boolean tonight(List<GameRow> history, LocalDate date) {
        if (history == null || date == null) {
            return false;
        }
        LocalDate y = date.minusDays(1);
        for (GameRow r : history) {
            if (r.date().equals(y) && r.minutes() > 0) {
                return true;
            }
        }
        return false;
    }

    /** True when that appearance itself was the second night of a back-to-back. */
    public static boolean wasBackToBack(List<GameRow> history, GameRow game) {
        return game != null && tonight(history, game.date());
    }

    public static Adjustment apply(Projection base, LeagueData data, String playerId, String team, LocalDate date,
                                   LocalDate seasonStart) {
        if (!base.ready()) {
            return new Adjustment(base, List.of());
        }
        List<GameRow> history = before(data.history(playerId), date);
        if (!tonight(history, date)) {
            return new Adjustment(base, List.of());
        }
        List<GameRow> season = new ArrayList<>();
        for (GameRow r : history) {
            if (r.team().equals(team) && !r.date().isBefore(seasonStart)) {
                season.add(r);
            }
        }
        List<GameRow> pool = season.size() >= MIN_SPLIT * 2 ? season : history;
        List<GameRow> b2b = new ArrayList<>(), rest = new ArrayList<>();
        for (GameRow r : pool) {
            if (wasBackToBack(history, r)) {
                b2b.add(r);
            } else {
                rest.add(r);
            }
        }
        if (b2b.size() < MIN_SPLIT || rest.size() < MIN_SPLIT) {
            return new Adjustment(base, List.of(String.format(Locale.US,
                    "Back-to-back (έπαιξε χθες): λίγα ιστορικά (%d B2B vs %d ξεκούραστα).", b2b.size(), rest.size())));
        }
        Delta d = delta(data, b2b, rest);
        String note = String.format(Locale.US,
                "Back-to-back (έπαιξε χθες, %d vs %d ξεκούραστα): %+.1f λεπτά, %+.1f usage, %+.1f FGA.",
                b2b.size(), rest.size(), d.mins, d.usg, d.fga);
        double dMin = cap(d.mins, CAP_MINS), dUsg = cap(d.usg, CAP_USG), dFga = cap(d.fga, CAP_FGA);
        double dFta = cap(d.fta, CAP_FTA), dFg3a = cap(d.fg3a, CAP_FG3A);
        if (tiny(dMin) && tiny(dUsg) && tiny(dFga)) {
            return new Adjustment(base, List.of(note));
        }
        return new Adjustment(ProjectionBuilder.shift(base, dMin, dUsg, dFga, dFta, dFg3a), List.of(note));
    }

    public static double matchScore(List<GameRow> history, GameRow game, LocalDate tonight) {
        if (!tonight(history, tonight)) {
            return 0;
        }
        return wasBackToBack(history, game) ? 1.0 : 0.0;
    }

    static Delta delta(LeagueData data, List<GameRow> match, List<GameRow> other) {
        return new Delta(meanMin(match) - meanMin(other), meanUsg(data, match) - meanUsg(data, other),
                meanStat(match, GameRow.FGA) - meanStat(other, GameRow.FGA),
                meanStat(match, GameRow.FTA) - meanStat(other, GameRow.FTA),
                meanStat(match, GameRow.FG3A) - meanStat(other, GameRow.FG3A));
    }

    private static List<GameRow> before(List<GameRow> history, LocalDate date) {
        List<GameRow> out = new ArrayList<>();
        for (GameRow r : history) {
            if (r.date().isBefore(date)) {
                out.add(r);
            }
        }
        return out;
    }

    private static double meanMin(List<GameRow> rows) {
        return rows.stream().mapToDouble(GameRow::minutes).average().orElse(0);
    }

    private static double meanUsg(LeagueData data, List<GameRow> rows) {
        double num = 0, den = 0;
        for (GameRow r : rows) {
            num += data.usage(r) * r.minutes();
            den += r.minutes();
        }
        return den > 0 ? num / den : 0;
    }

    private static double meanStat(List<GameRow> rows, int idx) {
        return rows.stream().mapToDouble(r -> r.v()[idx]).average().orElse(0);
    }

    private static double cap(double v, double lim) {
        return Math.max(-lim, Math.min(lim, v));
    }

    private static boolean tiny(double v) {
        return Math.abs(v) < 0.4;
    }

    record Delta(double mins, double usg, double fga, double fta, double fg3a) {
    }
}
