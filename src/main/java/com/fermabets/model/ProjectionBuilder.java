package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.model.FermabetsModel.Estimate;

import java.time.LocalDate;
import java.util.List;

/** Filter bands for an upcoming game, built only from the current season (roles change between seasons). */
public final class ProjectionBuilder {
    public static final int MIN_SEASON_GAMES = 3;
    static final double RECENCY = 0.85;
    public static final int LAST5 = Last5Form.GAMES;
    static final double LAST5_BLEND = Last5Form.BLEND;
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
        return build(seasonRows, usage, teamPace, oppPace, Last5Form.mix(seasonRows.size()));
    }

    /** {@code mix} is the last-5 share (0 = season only). Backtest uses 0 and {@link Last5Form#BLEND} on the same games. */
    public static Projection build(List<GameRow> seasonRows, double[] usage, double teamPace, double oppPace,
                                   double mix) {
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
        double seasonMins = wMin / wSum;
        double seasonUsg = totMin > 0 ? usgMin / totMin : Double.NaN;
        double seasonFgaRate = totMin > 0 ? fga / totMin : 0;
        double seasonFtaRate = totMin > 0 ? fta / totMin : 0;
        double seasonFg3aRate = totMin > 0 ? fg3a / totMin : 0;
        int k = Math.min(LAST5, n);
        double l5Min = 0, l5UsgMin = 0, l5Fga = 0, l5Fta = 0, l5Fg3a = 0;
        for (int i = n - k; i < n; i++) {
            GameRow r = seasonRows.get(i);
            l5Min += r.minutes();
            l5UsgMin += usage[i] * r.minutes();
            l5Fga += r.v()[GameRow.FGA];
            l5Fta += r.v()[GameRow.FTA];
            l5Fg3a += r.v()[GameRow.FG3A];
        }
        double last5Mins = l5Min / k;
        double last5Usg = l5Min > 0 ? l5UsgMin / l5Min : seasonUsg;
        double last5FgaRate = l5Min > 0 ? l5Fga / l5Min : 0;
        double last5FtaRate = l5Min > 0 ? l5Fta / l5Min : 0;
        double last5Fg3aRate = l5Min > 0 ? l5Fg3a / l5Min : 0;
        mix = n > LAST5 ? mix : 0.0;
        double mins = Last5Form.blend(seasonMins, last5Mins, mix);
        double usg = Double.isNaN(seasonUsg) ? Double.NaN : Last5Form.blend(seasonUsg, last5Usg, mix);
        double pFga = Last5Form.blend(seasonFgaRate, last5FgaRate, mix) * mins;
        double pFta = Last5Form.blend(seasonFtaRate, last5FtaRate, mix) * mins;
        double pFg3a = Last5Form.blend(seasonFg3aRate, last5Fg3aRate, mix) * mins;
        double pace = Double.isNaN(teamPace) ? oppPace : Double.isNaN(oppPace) ? teamPace : (teamPace + oppPace) / 2;
        return bands(n, mins, usg, pFga, pFta, pFg3a, pace);
    }

    /**
     * Same band widths as {@link #build}, shifted by historical lineup deltas. Pace is left alone. Weights of the
     * 55/45 model are not touched — only the filter inputs move.
     */
    public static Projection shift(Projection p, double dMin, double dUsg, double dFga, double dFta, double dFg3a) {
        if (!p.ready()) {
            return p;
        }
        double mins = clamp(p.minutes() + dMin, 0, 44);
        double usg = Double.isNaN(p.usage()) ? Double.NaN : clamp(p.usage() + dUsg, 3, 45);
        return bands(p.games(), mins, usg, Math.max(0, p.fga() + dFga), Math.max(0, p.fta() + dFta),
                Math.max(0, p.fg3a() + dFg3a), p.pace());
    }

    /**
     * Keep the model's centres for every null; filled values replace that centre only. Band widths follow the
     * same rules as {@link #build}. 55/45 weights are not touched.
     */
    public static Projection override(Projection p, Double minutes, Double usage, Double fga, Double fta,
                                      Double fg3a, Double pace) {
        if (!p.ready()) {
            return p;
        }
        double mins = minutes != null ? clamp(minutes, 0, 44) : p.minutes();
        double usg = usage != null ? clamp(usage, 3, 45) : p.usage();
        double fgaV = fga != null ? Math.max(0, fga) : p.fga();
        double ftaV = fta != null ? Math.max(0, fta) : p.fta();
        double fg3aV = fg3a != null ? Math.max(0, fg3a) : p.fg3a();
        double paceV = pace != null ? pace : p.pace();
        return bands(p.games(), mins, usg, fgaV, ftaV, fg3aV, paceV);
    }

    private static Projection bands(int games, double mins, double usg, double fga, double fta, double fg3a,
                                    double pace) {
        double fgaBand = Math.max(FGA_MIN_BAND, fga * FGA_PCT);
        return new Projection(games, mins, usg, fga, fta, fg3a, pace,
                band(mins, MIN_BAND), Double.isNaN(usg) ? null : band(usg, USG_BAND), band(fga, fgaBand),
                band(fta, FTA_BAND), band(fg3a, FG3A_BAND), Double.isNaN(pace) ? null : band(pace, PACE_BAND));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    static Estimate band(double centre, double half) {
        return Estimate.range(round1(Math.max(0, centre - half)), round1(centre + half));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
