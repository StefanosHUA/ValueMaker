package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.model.ProjectionBuilder.Projection;

import java.time.LocalDate;
import java.util.*;

/**
 * Roster-conditioned form: shifts tonight's bands from with-vs-without teammate averages, and exposes the same
 * split so hit rates can be taken from matching games. 55/45 weights stay the same.
 */
public final class LineupAdjuster {
    public static final int MIN_SPLIT = 3;
    public static final int RECENT = 5;
    public static final double TEAMMATE_MIN = 12.0;
    /** Extra weight on games whose roster matches tonight. Bühlmann share of the final p. */
    public static final double ROSTER_BOOST = 1.5;
    public static final double ROSTER_K = 5.0;
    public static final double ROSTER_SHARE = 0.40;
    static final double CAP_MINS = 8.0;
    static final double CAP_USG = 6.0;
    static final double CAP_FGA = 5.0;
    static final double CAP_FTA = 3.0;
    static final double CAP_FG3A = 3.0;

    private LineupAdjuster() {
    }

    public record Adjustment(Projection projection, List<String> notes) {
    }

    /** A teammate whose in/out state tonight is different from recent games and has enough history both ways. */
    public record Condition(String teammateId, String name, boolean out, int presentN, int absentN, double dMins,
                            double dUsg, double dFga) {
        public String label() {
            return (out ? "Χωρίς " : "Επιστροφή ") + name;
        }
    }

    /**
     * tonightOut: confirmed Out/OFS teammate ids. tonightBack: teammates expected to play who were missing recently.
     * names: player id → display name for the explanation line.
     */
    public static Adjustment apply(Projection base, LeagueData data, String playerId, String team, LocalDate date,
                                   LocalDate seasonStart, Set<String> tonightOut, Set<String> tonightBack,
                                   Map<String, String> names) {
        if (!base.ready()) {
            return new Adjustment(base, List.of());
        }
        List<Condition> conds = conditions(data, playerId, team, date, seasonStart, tonightOut, tonightBack, names);
        if (conds.isEmpty()) {
            return new Adjustment(base, List.of());
        }
        double dMin = 0, dUsg = 0, dFga = 0, dFta = 0, dFg3a = 0;
        List<String> notes = new ArrayList<>();
        for (Condition c : conds) {
            Optional<Delta> d = delta(data, season(data, playerId, team, seasonStart, date), c.teammateId(), team);
            Delta x = d.orElse(new Delta(c.dMins(), c.dUsg(), c.dFga(), 0, 0, c.presentN(), c.absentN()));
            double sign = c.out() ? 1.0 : -1.0;
            dMin += sign * x.mins;
            dUsg += sign * x.usg;
            dFga += sign * x.fga;
            dFta += sign * x.fta;
            dFg3a += sign * x.fg3a;
            notes.add(String.format(Locale.US,
                    "%s: ιστορικά %+.1f λεπτά, %+.1f usage, %+.1f FGA (%d αγώνες χωρίς / %d με).",
                    c.label(), sign * x.mins, sign * x.usg, sign * x.fga, x.absentN, x.presentN));
        }
        dMin = cap(dMin, CAP_MINS);
        dUsg = cap(dUsg, CAP_USG);
        dFga = cap(dFga, CAP_FGA);
        dFta = cap(dFta, CAP_FTA);
        dFg3a = cap(dFg3a, CAP_FG3A);
        if (tiny(dMin) && tiny(dUsg) && tiny(dFga)) {
            return new Adjustment(base, notes);
        }
        return new Adjustment(ProjectionBuilder.shift(base, dMin, dUsg, dFga, dFta, dFg3a), notes);
    }

    public static List<Condition> conditions(LeagueData data, String playerId, String team, LocalDate date,
                                             LocalDate seasonStart, Set<String> tonightOut, Set<String> tonightBack,
                                             Map<String, String> names) {
        List<Condition> out = new ArrayList<>();
        List<GameRow> season = season(data, playerId, team, seasonStart, date);
        Set<String> teammates = new LinkedHashSet<>();
        teammates.addAll(tonightOut);
        teammates.addAll(tonightBack);
        teammates.remove(playerId);
        for (String tid : teammates) {
            Optional<Delta> d = delta(data, season, tid, team);
            if (d.isEmpty()) {
                continue;
            }
            Delta x = d.get();
            double recent = recentPresence(data, season, tid, team);
            boolean isOut = tonightOut.contains(tid);
            boolean back = tonightBack.contains(tid) && !isOut;
            if (isOut && recent < 0.5) {
                continue;
            }
            if (back && recent > 0.4) {
                continue;
            }
            if (!isOut && !back) {
                continue;
            }
            out.add(new Condition(tid, names.getOrDefault(tid, tid), isOut, x.presentN, x.absentN, x.mins, x.usg,
                    x.fga));
        }
        return out;
    }

    /** 1 when every active teammate's in/out state matches tonight, else the fraction that match. */
    public static double matchScore(LeagueData data, String team, String gameId, List<Condition> conds) {
        if (conds == null || conds.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (Condition c : conds) {
            double m = minutes(data, c.teammateId(), gameId, team);
            if (c.out() ? m <= 0 : m >= TEAMMATE_MIN) {
                ok++;
            }
        }
        return ok / (double) conds.size();
    }

    /** How much of tonight's p comes from the same-roster hit rate. Grows with n as the season fills in. */
    public static double blendShare(int nMatch) {
        if (nMatch < MIN_SPLIT) {
            return 0;
        }
        return FermabetsModel.credibility(nMatch, ROSTER_K) * ROSTER_SHARE;
    }

    private static List<GameRow> season(LeagueData data, String playerId, String team, LocalDate seasonStart,
                                        LocalDate date) {
        List<GameRow> season = new ArrayList<>();
        for (GameRow r : data.history(playerId)) {
            if (r.team().equals(team) && !r.date().isBefore(seasonStart) && r.date().isBefore(date)) {
                season.add(r);
            }
        }
        return season;
    }

    static Optional<Delta> delta(LeagueData data, List<GameRow> season, String teammateId, String team) {
        List<GameRow> present = new ArrayList<>();
        List<GameRow> absent = new ArrayList<>();
        for (GameRow r : season) {
            double tMin = minutes(data, teammateId, r.gameId(), team);
            if (tMin >= TEAMMATE_MIN) {
                present.add(r);
            } else if (tMin <= 0) {
                absent.add(r);
            }
        }
        if (present.size() < MIN_SPLIT || absent.size() < MIN_SPLIT) {
            return Optional.empty();
        }
        return Optional.of(new Delta(
                meanMin(absent) - meanMin(present),
                meanUsg(data, absent) - meanUsg(data, present),
                meanStat(absent, GameRow.FGA) - meanStat(present, GameRow.FGA),
                meanStat(absent, GameRow.FTA) - meanStat(present, GameRow.FTA),
                meanStat(absent, GameRow.FG3A) - meanStat(present, GameRow.FG3A),
                present.size(), absent.size()));
    }

    static double recentPresence(LeagueData data, List<GameRow> season, String teammateId, String team) {
        int n = Math.min(RECENT, season.size());
        if (n == 0) {
            return 0.5;
        }
        int hit = 0;
        for (int i = season.size() - n; i < season.size(); i++) {
            if (minutes(data, teammateId, season.get(i).gameId(), team) >= TEAMMATE_MIN) {
                hit++;
            }
        }
        return hit / (double) n;
    }

    static double minutes(LeagueData data, String playerId, String gameId, String team) {
        for (GameRow r : data.history(playerId)) {
            if (r.gameId().equals(gameId) && r.team().equals(team)) {
                return r.minutes();
            }
        }
        return 0;
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

    record Delta(double mins, double usg, double fga, double fta, double fg3a, int presentN, int absentN) {
    }
}
