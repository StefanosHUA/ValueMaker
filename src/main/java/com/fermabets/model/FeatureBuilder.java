package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.domain.Stat;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Turns a player's prior games plus game context into a feature vector. */
public final class FeatureBuilder {
    public static final int MIN_PRIOR_GAMES = 8;
    public static final String[] NAMES = {
            "recent form (EWMA)", "recent minutes (EWMA)", "last 5 games", "home", "back-to-back",
            "well rested (4+ days)", "opponent allows", "game pace", "missing rotation minutes",
            "missing rotation points", "officiating (whistle)",
            "projected usage %", "usage budget effect", "role minutes", "role-based projection",
            "role continuity", "usage change vs form", "role shift (low continuity)",
            "usage change this season", "minutes change this season",
            "expected margin size (blowout risk)", "blowout risk x starter", "close game x starter",
            "expected margin (favourite +)", "expected game total"};
    private static final int FORM_GAMES = 30;
    private static final double HALF_LIFE = 5.0;

    /** Context of the game being predicted. Absentees and crew are only used for upcoming games. */
    public record Ctx(String team, String opp, LocalDate date, boolean home, String gameId,
                      Set<String> absentees, Collection<String> crew, Double mktMargin, Double mktTotal) {
        public Ctx(String team, String opp, LocalDate date, boolean home, String gameId, Set<String> absentees,
                   Collection<String> crew) {
            this(team, opp, date, home, gameId, absentees, crew, null, null);
        }
    }

    public record Sample(double[] x, double y, double ewma, double ewmaMin, LocalDate date, String playerId) {
    }

    private FeatureBuilder() {
    }

    public static double ewma(List<GameRow> prior, Stat stat, boolean minutes) {
        double num = 0, den = 0;
        int n = prior.size();
        for (int i = n - 1; i >= Math.max(0, n - FORM_GAMES); i--) {
            double w = Math.pow(0.5, (n - 1 - i) / HALF_LIFE);
            GameRow r = prior.get(i);
            num += w * (minutes ? r.minutes() : stat.of(r));
            den += w;
        }
        return den == 0 ? 0 : num / den;
    }

    /** Returns null when there is too little history. */
    public static double[] build(LeagueData data, List<GameRow> prior, Stat stat, Ctx ctx) {
        if (prior.size() < MIN_PRIOR_GAMES) {
            return null;
        }
        double form = ewma(prior, stat, false);
        double mins = ewma(prior, stat, true);
        double last5 = 0;
        int n = prior.size();
        for (int i = n - 5; i < n; i++) {
            last5 += stat.of(prior.get(i)) / 5.0;
        }
        String pid = prior.get(n - 1).playerId();
        LeagueData.Role role = ctx.gameId() != null
                ? data.playedRole(ctx.team(), ctx.date(), ctx.gameId())
                : data.upcomingRole(ctx.team(), ctx.date(), ctx.absentees(), pid);
        double[] info = role.players().get(pid);
        if (info == null) {
            return null;
        }
        double usg = info[1];
        double roleMin = info[2];
        double cont = info[3];
        double plain = data.plainUsage(pid, ctx.date());
        double roleStat = roleMin * data.roleRate(pid, ctx.date(), role, stat)
                * (1.0 + usageSensitivity(stat) * (role.scale() - 1.0));
        double[] sofar = data.seasonSoFar(pid, ctx.date());
        double trust = sofar == null ? 0.0 : Math.min(1.0, sofar[0] / 8.0);
        double seasonUsg = sofar == null ? 0.0 : trust * (sofar[1] - usg);
        double seasonMin = sofar == null ? 0.0 : trust * (sofar[2] - roleMin);
        // Bookmaker spread/total when we have them; otherwise our own team-strength estimate (also used in training).
        double margin = ctx.mktMargin() != null ? ctx.mktMargin()
                : data.expectedMargin(ctx.team(), ctx.opp(), ctx.date(), ctx.home());
        double totalRatio = ctx.mktTotal() != null ? data.totalRatio(ctx.mktTotal())
                : data.expectedTotalRatio(ctx.team(), ctx.opp(), ctx.date());
        double starter = (roleMin - 24.0) / 12.0;
        double blowout = Math.abs(margin) / 10.0;
        double close = Math.exp(-Math.pow(margin / 6.0, 2));
        int rest = data.restDays(ctx.team(), ctx.date());
        double[] missing = ctx.gameId() != null
                ? data.missingPlayed(ctx.team(), ctx.gameId(), ctx.date())
                : data.missingUpcoming(ctx.team(), ctx.date(), ctx.absentees() == null ? Set.of() : ctx.absentees());
        return new double[]{
                form, mins, last5,
                ctx.home() ? 1 : 0,
                rest <= 1 ? 1 : 0,
                rest >= 4 ? 1 : 0,
                data.oppAllowRatio(ctx.opp(), ctx.date(), stat),
                data.paceRatio(ctx.team(), ctx.opp(), ctx.date()),
                missing[0], missing[1],
                data.refFactor(ctx.crew()),
                usg, role.scale() - 1.0, roleMin, roleStat, cont, usg - plain, (1.0 - cont) * (roleStat - form),
                seasonUsg, seasonMin,
                blowout, blowout * starter, close * starter, margin / 10.0, totalRatio};
    }

    /** How strongly a stat follows the player's share of possessions used. */
    static double usageSensitivity(Stat stat) {
        return switch (stat) {
            case PTS, FG3M, TOV, PA -> 1.0;
            case AST, PRA, PR -> 0.6;
            case RA -> 0.4;
            default -> 0.15;
        };
    }

    /** Every historical appearance with enough prior games becomes a training sample. */
    public static List<Sample> samples(LeagueData data, Stat stat, LocalDate before) {
        List<Sample> out = new java.util.ArrayList<>();
        for (var e : data.byPlayer().entrySet()) {
            List<GameRow> rows = e.getValue();
            for (int i = FeatureBuilder.MIN_PRIOR_GAMES; i < rows.size(); i++) {
                GameRow r = rows.get(i);
                if (before != null && !r.date().isBefore(before)) {
                    break;
                }
                List<GameRow> prior = rows.subList(0, i);
                Ctx ctx = new Ctx(r.team(), r.opp(), r.date(), r.home(), r.gameId(), null,
                        data.crew(r.gameId()));
                double[] x = build(data, prior, stat, ctx);
                if (x == null || x[1] < 10.0) {
                    continue;
                }
                out.add(new Sample(x, stat.of(r), x[0], x[1], r.date(), r.playerId()));
            }
        }
        return out;
    }
}
