package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.model.FermabetsModel.Estimate;

import java.time.LocalDate;
import java.util.List;

/** Filter bands for an upcoming game, built only from the current season (roles change between seasons). */
public final class ProjectionBuilder {
    public static final int MIN_SEASON_GAMES = 3;
    static final double RECENCY = 0.85;
    static final double MIN_BAND = 2.0;
    static final double USG_BAND = 2.5;
    static final double FGA_PCT = 0.15;
    static final double FGA_MIN_BAND = 1.5;
    static final double FTA_BAND = 1.0;
    static final double FG3A_BAND = 1.0;
    static final double PACE_BAND = 2.0;

    private ProjectionBuilder() {
    }

    /** games: this season's appearances; bands null when there are fewer than MIN_SEASON_GAMES. */
    public record Projection(int games, double minutes, double usage, double fga, double fta, double fg3a,
                             double pace, Estimate minsBand, Estimate usgBand, Estimate fgaBand, Estimate ftaBand,
                             Estimate fg3aBand, Estimate paceBand) {
        public boolean ready() {
            return minsBand != null;
        }
    }

    /** seasonRows are sorted by date; usage values match them one to one. Pace values may be NaN. */
    public static Projection build(List<GameRow> seasonRows, double[] usage, double teamPace, double oppPace) {
        int n = seasonRows.size();
        if (n < MIN_SEASON_GAMES) {
            return new Projection(n, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    null, null, null, null, null, null);
        }
        double wSum = 0, wMin = 0, totMin = 0, usgMin = 0, fga = 0, fta = 0, fg3a = 0;
        for (int i = 0; i < n; i++) {
            GameRow r = seasonRows.get(i);
            double w = Math.pow(RECENCY, n - 1 - i);
            wSum += w;
            wMin += w * r.minutes();
            totMin += r.minutes();
            usgMin += usage[i] * r.minutes();
            fga += r.v()[GameRow.FGA];
            fta += r.v()[GameRow.FTA];
            fg3a += r.v()[GameRow.FG3A];
        }
        double mins = wMin / wSum;
        double usg = totMin > 0 ? usgMin / totMin : Double.NaN;
        double pFga = totMin > 0 ? fga / totMin * mins : 0;
        double pFta = totMin > 0 ? fta / totMin * mins : 0;
        double pFg3a = totMin > 0 ? fg3a / totMin * mins : 0;
        double pace = Double.isNaN(teamPace) ? oppPace : Double.isNaN(oppPace) ? teamPace : (teamPace + oppPace) / 2;
        double fgaBand = Math.max(FGA_MIN_BAND, pFga * FGA_PCT);
        return new Projection(n, mins, usg, pFga, pFta, pFg3a, pace,
                band(mins, MIN_BAND), Double.isNaN(usg) ? null : band(usg, USG_BAND), band(pFga, fgaBand),
                band(pFta, FTA_BAND), band(pFg3a, FG3A_BAND), Double.isNaN(pace) ? null : band(pace, PACE_BAND));
    }

    static Estimate band(double centre, double half) {
        return Estimate.range(round1(Math.max(0, centre - half)), round1(centre + half));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
