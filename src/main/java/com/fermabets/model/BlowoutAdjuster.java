package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.model.ProjectionBuilder.Projection;
import com.fermabets.service.WarningService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * When tonight looks like a blowout, shift minutes/usage/rates toward the player's games that were expected
 * blowouts and finished with a large margin. 55/45 weights stay the same.
 */
public final class BlowoutAdjuster {
    public static final double THRESHOLD = WarningService.BLOWOUT_SPREAD;
    public static final int MIN_SPLIT = 3;
    public static final double MATCH_BOOST = 1.5;
    static final double CAP_MINS = 8.0;
    static final double CAP_USG = 6.0;
    static final double CAP_FGA = 5.0;
    static final double CAP_FTA = 3.0;
    static final double CAP_FG3A = 3.0;

    private BlowoutAdjuster() {
    }

    public record Adjustment(Projection projection, List<String> notes) {
    }

    /**
     * tonightHcap uses the warning convention: negative = tonight's team is favourite.
     * expectedByGame: gameId → expected margin for the player's team (positive = favourite). Null = use ratings
     * and/or stored home handicaps (date|home|away → home spread).
     */
    public static Adjustment apply(Projection base, LeagueData data, String playerId, String team, LocalDate date,
                                   LocalDate seasonStart, double tonightHcap, Map<String, Double> expectedByGame,
                                   Map<String, Double> homeHandicaps) {
        if (!base.ready() || !WarningService.isBlowout(tonightHcap)) {
            return new Adjustment(base, List.of());
        }
        boolean fav = tonightHcap < 0;
        List<GameRow> season = new ArrayList<>();
        for (GameRow r : data.history(playerId)) {
            if (r.team().equals(team) && !r.date().isBefore(seasonStart) && r.date().isBefore(date)) {
                season.add(r);
            }
        }
        List<GameRow> pool = season.size() >= MIN_SPLIT * 2 ? season : data.history(playerId).stream()
                .filter(r -> r.date().isBefore(date)).toList();
        List<GameRow> preferred = new ArrayList<>(), expected = new ArrayList<>(), actual = new ArrayList<>(),
                other = new ArrayList<>();
        for (GameRow r : pool) {
            int kind = classify(data, r, fav, expectedByGame, homeHandicaps);
            if (kind == 3) {
                preferred.add(r);
                expected.add(r);
                actual.add(r);
            } else if (kind == 2) {
                expected.add(r);
            } else if (kind == 1) {
                actual.add(r);
            } else {
                other.add(r);
            }
        }
        List<GameRow> match = preferred.size() >= MIN_SPLIT ? preferred
                : expected.size() >= MIN_SPLIT ? expected
                : actual.size() >= MIN_SPLIT ? actual : List.of();
        if (match.size() < MIN_SPLIT || other.size() < MIN_SPLIT) {
            return new Adjustment(base, List.of(String.format(Locale.US,
                    "Blowout (%s, handicap %+.1f): λίγα ιστορικά (αναμενόμενα+τελικά %d, αναμενόμενα %d, τελικά %d).",
                    fav ? "φαβορί" : "αουτσάιντερ", tonightHcap, preferred.size(), expected.size(), actual.size())));
        }
        Delta d = delta(data, match, other);
        String how = match == preferred ? "αναμενόμενο + τελικό blowout"
                : match == expected ? "αναμενόμενο blowout" : "τελικό σκορ blowout";
        String note = String.format(Locale.US,
                "Blowout %s (handicap %+.1f, %s, %d vs %d κανονικά): %+.1f λεπτά, %+.1f usage, %+.1f FGA.",
                fav ? "φαβορί" : "αουτσάιντερ", tonightHcap, how, match.size(), other.size(), d.mins, d.usg, d.fga);
        double dMin = cap(d.mins, CAP_MINS), dUsg = cap(d.usg, CAP_USG), dFga = cap(d.fga, CAP_FGA);
        double dFta = cap(d.fta, CAP_FTA), dFg3a = cap(d.fg3a, CAP_FG3A);
        if (tiny(dMin) && tiny(dUsg) && tiny(dFga)) {
            return new Adjustment(base, List.of(note));
        }
        return new Adjustment(ProjectionBuilder.shift(base, dMin, dUsg, dFga, dFta, dFg3a), List.of(note));
    }

    /** 1 when the past game is a preferred (expected + actual) match for tonight's blowout role. */
    public static double matchScore(LeagueData data, GameRow r, double tonightHcap, Map<String, Double> expectedByGame,
                                    Map<String, Double> homeHandicaps) {
        if (!WarningService.isBlowout(tonightHcap)) {
            return 0;
        }
        return classify(data, r, tonightHcap < 0, expectedByGame, homeHandicaps) == 3 ? 1.0 : 0.0;
    }

    /**
     * 3 = expected and actual blowout in tonight's role, 2 = expected only, 1 = actual only, 0 = neither.
     */
    static int classify(LeagueData data, GameRow r, boolean tonightFav, Map<String, Double> expectedByGame,
                        Map<String, Double> homeHandicaps) {
        double exp = expectedMargin(data, r, expectedByGame, homeHandicaps);
        double act = data.scoreMargin(r.gameId(), r.team());
        boolean expOk = !Double.isNaN(exp) && (tonightFav ? exp > THRESHOLD : exp < -THRESHOLD);
        boolean actOk = !Double.isNaN(act) && (tonightFav ? act > THRESHOLD : act < -THRESHOLD);
        if (expOk && actOk) {
            return 3;
        }
        if (expOk) {
            return 2;
        }
        return actOk ? 1 : 0;
    }

    static double expectedMargin(LeagueData data, GameRow r, Map<String, Double> expectedByGame,
                                 Map<String, Double> homeHandicaps) {
        if (expectedByGame != null && expectedByGame.containsKey(r.gameId())) {
            return expectedByGame.get(r.gameId());
        }
        if (homeHandicaps != null) {
            String home = r.home() ? r.team() : r.opp();
            String away = r.home() ? r.opp() : r.team();
            Double hs = homeHandicaps.get(r.date() + "|" + home + "|" + away);
            if (hs != null) {
                return r.home() ? -hs : hs;
            }
        }
        return data.expectedMargin(r.team(), r.opp(), r.date(), r.home());
    }

    static Delta delta(LeagueData data, List<GameRow> match, List<GameRow> other) {
        return new Delta(meanMin(match) - meanMin(other), meanUsg(data, match) - meanUsg(data, other),
                meanStat(match, GameRow.FGA) - meanStat(other, GameRow.FGA),
                meanStat(match, GameRow.FTA) - meanStat(other, GameRow.FTA),
                meanStat(match, GameRow.FG3A) - meanStat(other, GameRow.FG3A));
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
