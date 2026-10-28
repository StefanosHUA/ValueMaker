package com.fermabets.model;

import com.fermabets.domain.GameRow;
import com.fermabets.domain.Stat;
import com.fermabets.domain.TeamGame;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** In-memory view of one league's history with the context lookups used for features. */
public final class LeagueData {
    public static final int ROTATION_WINDOW = 10;
    public static final double ROTATION_MIN_MINUTES = 15.0;
    private static final int FORM_WINDOW = 15;

    private final String league;
    private final Map<String, List<GameRow>> byPlayer = new HashMap<>();
    private final Map<String, List<TeamGame>> teamGames = new HashMap<>();
    private final Map<String, double[]> teamTotals = new HashMap<>();
    private final Map<String, Map<String, double[]>> roster = new HashMap<>();
    private final Map<String, double[]> missingCache = new HashMap<>();
    private final Map<String, Double> officialFactor = new HashMap<>();
    private final Map<Stat, Double> leagueTeamMean = new EnumMap<>(Stat.class);
    private double leagueMeanPoss;
    private final Map<String, List<String>> officials;

    private static final int ROLE_ROWS = 30;
    private static final double ROLE_HALF_LIFE = 8.0;
    private static final double CONT_FLOOR = 0.1;
    private static final double PRIOR_USAGE = 20.0;
    private static final double PRIOR_WEIGHT = 60.0;
    private static final double ROSTER_DAYS = 150;
    private static final double SEASON_BOOST = 3.0;
    private static final double ROTATION_MIN = 15.0;
    private final Map<String, Double> teamMinutes = new HashMap<>();
    private final Map<String, LocalDate> keyDate = new HashMap<>();
    private final Map<String, Set<String>> rosterBefore = new HashMap<>();
    private final Map<String, Map<String, LocalDate>> rosterState = new HashMap<>();
    private final Map<String, Role> roleCache = new HashMap<>();
    private final Map<String, TeamGame> tgByKey = new HashMap<>();
    private final Map<String, Double> rawRatingCache = new HashMap<>();
    private final Map<String, Double> ratingCache = new HashMap<>();
    private double leagueHca = 2.5;
    private double leaguePts = 100.0;
    private double leagueTeamMinutes = 240.0;
    private double leagueRotationUsage = 20.0;

    /**
     * A team's projected role structure for one game. players: id -> {raw usage, budget-adjusted usage,
     * minutes when playing (absence-adjusted), continuity}. scale is the usage-budget correction.
     */
    public record Role(Set<String> roster, Set<String> absent, Set<String> assumedOut,
                       Map<String, double[]> players, Map<String, double[]> weights, double scale) {
    }

    public LeagueData(String league, List<GameRow> rows, List<TeamGame> games, Map<String, List<String>> officials) {
        this.league = league;
        this.officials = officials;
        for (GameRow r : rows) {
            byPlayer.computeIfAbsent(r.playerId(), k -> new ArrayList<>()).add(r);
            teamTotals.computeIfAbsent(r.gameId() + "|" + r.team(), k -> new double[GameRow.N_STATS]);
            double[] t = teamTotals.get(r.gameId() + "|" + r.team());
            for (int i = 0; i < GameRow.N_STATS; i++) {
                t[i] += r.v()[i];
            }
            roster.computeIfAbsent(r.gameId() + "|" + r.team(), k -> new HashMap<>())
                    .put(r.playerId(), new double[]{r.minutes(), r.v()[GameRow.PTS]});
            teamMinutes.merge(r.gameId() + "|" + r.team(), r.minutes(), Double::sum);
            keyDate.put(r.gameId() + "|" + r.team(), r.date());
        }
        buildRosterState();
        if (!teamMinutes.isEmpty()) {
            leagueTeamMinutes = teamMinutes.values().stream().mapToDouble(Double::doubleValue).average().orElse(240);
        }
        double um = 0, m = 0;
        for (List<GameRow> l : byPlayer.values()) {
            for (GameRow r : l) {
                if (r.minutes() >= ROTATION_MIN) {
                    um += usage(r) * r.minutes();
                    m += r.minutes();
                }
            }
        }
        if (m > 0) {
            leagueRotationUsage = um / m;
        }
        byPlayer.values().forEach(l -> l.sort(Comparator.comparing(GameRow::date)));
        for (TeamGame g : games) {
            teamGames.computeIfAbsent(g.team(), k -> new ArrayList<>()).add(g);
        }
        teamGames.values().forEach(l -> l.sort(Comparator.comparing(TeamGame::date)));
        computeLeagueMeans(games);
        computeOfficials(games, officials);
        for (TeamGame g : games) {
            tgByKey.put(g.gameId() + "|" + g.team(), g);
        }
        double hs = 0, ps = 0;
        int hn = 0;
        for (TeamGame g : games) {
            TeamGame o = tgByKey.get(g.gameId() + "|" + g.opp());
            ps += g.pts();
            if (g.home() && o != null) {
                hs += g.pts() - o.pts();
                hn++;
            }
        }
        if (hn > 0) {
            leagueHca = hs / hn;
        }
        if (!games.isEmpty()) {
            leaguePts = ps / games.size();
        }
    }

    private static final int RATING_GAMES = 25;
    private static final double RATING_HALF_LIFE = 12.0;
    private static final double RATING_SHRINK = 3.0;

    private double margin(TeamGame g) {
        TeamGame o = tgByKey.get(g.gameId() + "|" + g.opp());
        return o == null ? Double.NaN : g.pts() - o.pts() - (g.home() ? leagueHca : -leagueHca);
    }

    /** Venue-adjusted average margin (shrunk towards 0) from the last games; opponent strength not yet removed. */
    private double rawRating(String team, LocalDate date) {
        return rawRatingCache.computeIfAbsent(team + "|" + date, k -> {
            List<TeamGame> list = teamGames.getOrDefault(team, List.of());
            int idx = idxBefore(list, date);
            double num = 0, den = 0;
            for (int i = idx - 1; i >= Math.max(0, idx - RATING_GAMES); i--) {
                double m = margin(list.get(i));
                if (!Double.isNaN(m)) {
                    double w = Math.pow(0.5, (idx - 1 - i) / RATING_HALF_LIFE);
                    num += w * m;
                    den += w;
                }
            }
            return num / (den + RATING_SHRINK);
        });
    }

    /** Team strength in points of margin vs an average team on a neutral floor, opponent-adjusted. */
    public double rating(String team, LocalDate date) {
        return ratingCache.computeIfAbsent(team + "|" + date, k -> {
            List<TeamGame> list = teamGames.getOrDefault(team, List.of());
            int idx = idxBefore(list, date);
            double num = 0, den = 0;
            for (int i = idx - 1; i >= Math.max(0, idx - RATING_GAMES); i--) {
                TeamGame g = list.get(i);
                double m = margin(g);
                if (!Double.isNaN(m)) {
                    double w = Math.pow(0.5, (idx - 1 - i) / RATING_HALF_LIFE);
                    num += w * (m + rawRating(g.opp(), date));
                    den += w;
                }
            }
            return num / (den + RATING_SHRINK);
        });
    }

    /** Our own expected margin for the team (positive = favourite) when no bookmaker price is available. */
    public double expectedMargin(String team, String opp, LocalDate date, boolean home) {
        return rating(team, date) - rating(opp, date) + (home ? leagueHca : -leagueHca);
    }

    /** Expected combined points of the two teams relative to the league norm (0 = average). */
    public double expectedTotalRatio(String team, String opp, LocalDate date) {
        double a = recentPts(team, date), b = recentPts(opp, date);
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return 0.0;
        }
        return (a + b) / (2.0 * leaguePts) - 1.0;
    }

    /** Total points implied by a bookmaker total, relative to the league norm. */
    public double totalRatio(double marketTotal) {
        return marketTotal / (2.0 * leaguePts) - 1.0;
    }

    private double recentPts(String team, LocalDate date) {
        List<TeamGame> list = teamGames.getOrDefault(team, List.of());
        int idx = idxBefore(list, date);
        int from = Math.max(0, idx - FORM_WINDOW);
        if (idx - from < 5) {
            return Double.NaN;
        }
        double s = 0;
        for (int i = from; i < idx; i++) {
            s += list.get(i).pts();
        }
        return s / (idx - from);
    }

    public String league() {
        return league;
    }

    public Map<String, List<GameRow>> byPlayer() {
        return byPlayer;
    }

    public List<GameRow> history(String playerId) {
        return byPlayer.getOrDefault(playerId, List.of());
    }

    public int rowCount() {
        return byPlayer.values().stream().mapToInt(List::size).sum();
    }

    private void computeLeagueMeans(List<TeamGame> games) {
        leagueMeanPoss = games.stream().mapToDouble(TeamGame::poss).average().orElse(1.0);
        for (Stat s : Stat.values()) {
            leagueTeamMean.put(s, teamTotals.values().stream().mapToDouble(s::of).average().orElse(1.0));
        }
    }

    private void computeOfficials(List<TeamGame> games, Map<String, List<String>> officials) {
        Map<String, Double> whistle = new HashMap<>();
        for (TeamGame g : games) {
            whistle.merge(g.gameId(), g.fta() + g.pf(), Double::sum);
        }
        double mean = officials.keySet().stream().filter(whistle::containsKey)
                .mapToDouble(whistle::get).average().orElse(0.0);
        if (mean <= 0) {
            return;
        }
        Map<String, double[]> acc = new HashMap<>();
        for (var e : officials.entrySet()) {
            Double w = whistle.get(e.getKey());
            if (w == null) {
                continue;
            }
            for (String o : e.getValue()) {
                double[] a = acc.computeIfAbsent(o, k -> new double[2]);
                a[0] += w;
                a[1] += 1;
            }
        }
        double prior = 25.0;
        acc.forEach((o, a) -> officialFactor.put(o, ((a[0] + prior * mean) / (a[1] + prior)) / mean - 1.0));
    }

    /** Mean whistle factor of the crew (0 = league average); 0 when unknown. */
    public double refFactor(Collection<String> crew) {
        if (crew == null || crew.isEmpty()) {
            return 0.0;
        }
        return crew.stream().mapToDouble(o -> officialFactor.getOrDefault(o, 0.0)).average().orElse(0.0);
    }

    public List<String> crew(String gameId) {
        return officials.getOrDefault(gameId, List.of());
    }

    public Map<String, Double> officialFactors() {
        return officialFactor;
    }

    private int idxBefore(List<TeamGame> list, LocalDate date) {
        int lo = 0, hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).date().isBefore(date)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Days since the team's previous game (capped at 7). */
    public int restDays(String team, LocalDate date) {
        List<TeamGame> list = teamGames.getOrDefault(team, List.of());
        int idx = idxBefore(list, date);
        if (idx == 0) {
            return 7;
        }
        return (int) Math.min(7, ChronoUnit.DAYS.between(list.get(idx - 1).date(), date));
    }

    /** Relative amount the opponent has allowed recently for this stat versus the league (0 = average). */
    public double oppAllowRatio(String opp, LocalDate date, Stat stat) {
        List<TeamGame> list = teamGames.getOrDefault(opp, List.of());
        int idx = idxBefore(list, date);
        int from = Math.max(0, idx - FORM_WINDOW);
        double sum = 0;
        int n = 0;
        for (int i = from; i < idx; i++) {
            TeamGame g = list.get(i);
            double[] t = teamTotals.get(g.gameId() + "|" + g.opp());
            if (t != null) {
                sum += stat.of(t);
                n++;
            }
        }
        if (n < 5) {
            return 0.0;
        }
        return (sum / n) / leagueTeamMean.get(stat) - 1.0;
    }

    /** Combined recent possessions of both teams versus the league average (0 = average). */
    public double paceRatio(String team, String opp, LocalDate date) {
        double a = recentPoss(team, date);
        double b = recentPoss(opp, date);
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return 0.0;
        }
        return (a + b) / 2.0 / leagueMeanPoss - 1.0;
    }

    private double recentPoss(String team, LocalDate date) {
        List<TeamGame> list = teamGames.getOrDefault(team, List.of());
        int idx = idxBefore(list, date);
        int from = Math.max(0, idx - FORM_WINDOW);
        if (idx - from < 5) {
            return Double.NaN;
        }
        double s = 0;
        for (int i = from; i < idx; i++) {
            s += list.get(i).poss();
        }
        return s / (idx - from);
    }

    /** Rotation players (id -> {avgMinutes, avgPoints}) from the team's last games before the date. */
    public Map<String, double[]> rotation(String team, LocalDate date) {
        List<TeamGame> list = teamGames.getOrDefault(team, List.of());
        int idx = idxBefore(list, date);
        Map<String, double[]> acc = new HashMap<>();
        for (int i = Math.max(0, idx - ROTATION_WINDOW); i < idx; i++) {
            Map<String, double[]> r = roster.get(list.get(i).gameId() + "|" + team);
            if (r == null) {
                continue;
            }
            r.forEach((p, mp) -> {
                double[] a = acc.computeIfAbsent(p, k -> new double[3]);
                a[0] += mp[0];
                a[1] += mp[1];
                a[2] += 1;
            });
        }
        Map<String, double[]> out = new HashMap<>();
        acc.forEach((p, a) -> {
            if (a[2] >= 5 && a[0] / a[2] >= ROTATION_MIN_MINUTES) {
                out.put(p, new double[]{a[0] / a[2], a[1] / a[2]});
            }
        });
        return out;
    }

    /** {missing minutes, missing points per game} of rotation players absent from this played game. */
    public double[] missingPlayed(String team, String gameId, LocalDate date) {
        return missingCache.computeIfAbsent(gameId + "|" + team, k -> {
            Map<String, double[]> played = roster.getOrDefault(gameId + "|" + team, Map.of());
            return sumMissing(rotation(team, date), played.keySet());
        });
    }

    /** Same for an upcoming game with a known absentee list. */
    public double[] missingUpcoming(String team, LocalDate date, Set<String> absentees) {
        Map<String, double[]> rot = rotation(team, date);
        Set<String> present = new HashSet<>(rot.keySet());
        present.removeAll(absentees);
        return sumMissing(rot, present);
    }

    private static double[] sumMissing(Map<String, double[]> rotation, Set<String> present) {
        double mins = 0, pts = 0;
        for (var e : rotation.entrySet()) {
            if (!present.contains(e.getKey())) {
                mins += e.getValue()[0];
                pts += e.getValue()[1];
            }
        }
        return new double[]{mins, pts};
    }

    // ---------------------------------------------------------------------------------------------------------
    // Usage / role model
    // ---------------------------------------------------------------------------------------------------------

    private void buildRosterState() {
        Map<LocalDate, List<String>> byDate = new TreeMap<>();
        keyDate.forEach((k, d) -> byDate.computeIfAbsent(d, x -> new ArrayList<>()).add(k));
        Map<String, String> playerTeam = new HashMap<>();
        for (var e : byDate.entrySet()) {
            LocalDate d = e.getKey();
            for (String key : e.getValue()) {
                Set<String> snap = new HashSet<>();
                rosterState.getOrDefault(teamOf(key), Map.of()).forEach((p, last) -> {
                    if (!last.isBefore(d.minusDays((long) ROSTER_DAYS))) {
                        snap.add(p);
                    }
                });
                rosterBefore.put(key, snap);
            }
            for (String key : e.getValue()) {
                String team = teamOf(key);
                for (String p : roster.get(key).keySet()) {
                    String old = playerTeam.put(p, team);
                    if (old != null && !old.equals(team)) {
                        rosterState.get(old).remove(p);
                    }
                    rosterState.computeIfAbsent(team, k -> new HashMap<>()).put(p, d);
                }
            }
        }
    }

    private static String teamOf(String key) {
        return key.substring(key.lastIndexOf('|') + 1);
    }

    /** Players whose latest appearance was for this team within the last ~5 months. */
    public Set<String> currentRoster(String team, LocalDate date) {
        Set<String> out = new HashSet<>();
        rosterState.getOrDefault(team, Map.of()).forEach((p, last) -> {
            if (!last.isBefore(date.minusDays((long) ROSTER_DAYS))) {
                out.add(p);
            }
        });
        return out;
    }

    /** Possessions-used share while on the floor: 20 is average, stars are 28+. */
    public double usage(GameRow r) {
        String key = r.gameId() + "|" + r.team();
        double[] t = teamTotals.get(key);
        Double tm = teamMinutes.get(key);
        if (t == null || tm == null || r.minutes() <= 0) {
            return PRIOR_USAGE;
        }
        double teamUsed = t[GameRow.FGA] + 0.44 * t[GameRow.FTA] + t[GameRow.TOV];
        double used = r.v()[GameRow.FGA] + 0.44 * r.v()[GameRow.FTA] + r.v()[GameRow.TOV];
        if (teamUsed <= 0) {
            return PRIOR_USAGE;
        }
        return Math.max(3.0, Math.min(45.0, 100.0 * used * (tm / 5.0) / (r.minutes() * teamUsed)));
    }

    public List<GameRow> lastRows(String playerId, LocalDate date) {
        List<GameRow> h = byPlayer.getOrDefault(playerId, List.of());
        int lo = 0, hi = h.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (h.get(mid).date().isBefore(date)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return h.subList(Math.max(0, lo - ROLE_ROWS), lo);
    }

    /** Share of that game's other-player minutes that came from players on today's roster. */
    private double continuity(GameRow row, String playerId, Set<String> currentRoster) {
        Map<String, double[]> r = roster.get(row.gameId() + "|" + row.team());
        if (r == null) {
            return 0.0;
        }
        double tot = 0, in = 0;
        for (var e : r.entrySet()) {
            if (e.getKey().equals(playerId)) {
                continue;
            }
            tot += e.getValue()[0];
            if (currentRoster.contains(e.getKey())) {
                in += e.getValue()[0];
            }
        }
        return tot > 0 ? in / tot : 0.0;
    }

    /** Recency weight x how similar the game's supporting cast is to today's (so old-team games fade out). */
    private double[] roleWeights(List<GameRow> rows, String playerId, Set<String> currentRoster, LocalDate date) {
        int n = rows.size();
        double[] w = new double[n];
        int seasonStart = seasonStart(rows);
        for (int i = 0; i < n; i++) {
            double cont = continuity(rows.get(i), playerId, currentRoster);
            double gap = ChronoUnit.DAYS.between(rows.get(i).date(), date) > 100 ? 0.75 : 1.0;
            double season = i >= seasonStart && seasonStart > 0 ? SEASON_BOOST : 1.0;
            w[i] = Math.pow(0.5, (n - 1 - i) / ROLE_HALF_LIFE) * (CONT_FLOOR + (1 - CONT_FLOOR) * cont) * gap * season;
        }
        return w;
    }

    /** Role for an upcoming game (absences declared by the user plus long-term absentees inferred from the box scores). */
    public Role upcomingRole(String team, LocalDate date, Set<String> declaredAbsent, String extraPlayer) {
        Set<String> rost = currentRoster(team, date);
        if (extraPlayer != null) {
            rost.add(extraPlayer);
        }
        return buildRole(team, date, rost, declaredAbsent == null ? Set.of() : declaredAbsent, true);
    }

    /** Role for a historical game: who actually played is known, rostered players who did not are absent. */
    public Role playedRole(String team, LocalDate date, String gameId) {
        String key = gameId + "|" + team;
        Role cached = roleCache.get(key);
        if (cached != null) {
            return cached;
        }
        Set<String> played = roster.getOrDefault(key, Map.of()).keySet();
        Set<String> before = rosterBefore.getOrDefault(key, Set.of());
        Set<String> all = new HashSet<>(before);
        all.addAll(played);
        Set<String> absent = new HashSet<>(before);
        absent.removeAll(played);
        Role r = buildRole(team, date, all, absent, false);
        roleCache.put(key, r);
        return r;
    }

    private Role buildRole(String team, LocalDate date, Set<String> rost, Set<String> declaredAbsent,
                           boolean inferAbsent) {
        Map<String, double[]> info = new HashMap<>();
        Map<String, double[]> weights = new HashMap<>();
        for (String p : rost) {
            List<GameRow> rows = lastRows(p, date);
            if (rows.isEmpty()) {
                continue;
            }
            double[] w = roleWeights(rows, p, rost, date);
            double sw = 0, swm = 0, swmu = 0, cont = 0;
            for (int i = 0; i < rows.size(); i++) {
                GameRow r = rows.get(i);
                sw += w[i];
                swm += w[i] * r.minutes();
                swmu += w[i] * r.minutes() * usage(r);
            }
            int from = Math.max(0, rows.size() - 10);
            for (int i = from; i < rows.size(); i++) {
                cont += continuity(rows.get(i), p, rost);
            }
            double u = (swmu + PRIOR_USAGE * PRIOR_WEIGHT) / (swm + PRIOR_WEIGHT);
            double m = sw > 0 ? swm / sw : 0;
            info.put(p, new double[]{u, u, m, cont / (rows.size() - from)});
            weights.put(p, w);
        }
        Set<String> absent = new HashSet<>(declaredAbsent);
        absent.retainAll(rost);
        Set<String> assumed = new HashSet<>();
        if (inferAbsent) {
            List<TeamGame> tg = teamGames.getOrDefault(team, List.of());
            int idx = idxBefore(tg, date);
            if (idx >= 3) {
                for (String p : info.keySet()) {
                    if (absent.contains(p) || info.get(p)[2] < 12.0) {
                        continue;
                    }
                    boolean seen = false;
                    boolean known = false;
                    for (int i = idx - 3; i < idx; i++) {
                        Map<String, double[]> r = roster.get(tg.get(i).gameId() + "|" + team);
                        if (r != null) {
                            known = true;
                            seen |= r.containsKey(p);
                        }
                    }
                    if (known && !seen) {
                        assumed.add(p);
                    }
                }
                absent.addAll(assumed);
            }
        }
        double absentMinutes = 0, presentRotMinutes = 0;
        for (var e : info.entrySet()) {
            boolean rotation = e.getValue()[2] >= ROTATION_MIN;
            if (absent.contains(e.getKey())) {
                absentMinutes += rotation ? e.getValue()[2] : 0;
            } else if (rotation) {
                presentRotMinutes += e.getValue()[2];
            }
        }
        double minuteBoost = presentRotMinutes > 0 ? 1 + 0.8 * absentMinutes / presentRotMinutes : 1.0;
        double um = 0, mm = 0;
        for (var e : info.entrySet()) {
            if (!absent.contains(e.getKey()) && e.getValue()[2] >= ROTATION_MIN) {
                um += e.getValue()[0] * e.getValue()[2];
                mm += e.getValue()[2];
            }
        }
        double scale = 1.0;
        if (mm > 0) {
            scale = Math.max(0.8, Math.min(1.25, leagueRotationUsage / (um / mm)));
        }
        for (var e : info.entrySet()) {
            double[] v = e.getValue();
            if (absent.contains(e.getKey())) {
                v[2] = 0;
                continue;
            }
            v[1] = v[0] * scale;
            if (v[2] >= ROTATION_MIN) {
                v[2] = Math.min(v[2] + 6.0, v[2] * minuteBoost);
            }
        }
        return new Role(rost, absent, assumed, info, weights, scale);
    }

    /** Continuity-weighted per-minute rate of the stat for the player under the given role. */
    public double roleRate(String playerId, LocalDate date, Role role, Stat stat) {
        List<GameRow> rows = lastRows(playerId, date);
        double[] w = role.weights().get(playerId);
        if (w == null || w.length != rows.size()) {
            w = roleWeights(rows, playerId, role.roster(), date);
        }
        double num = 0, den = 0;
        for (int i = 0; i < rows.size(); i++) {
            num += w[i] * stat.of(rows.get(i));
            den += w[i] * rows.get(i).minutes();
        }
        return den > 0 ? num / den : 0.0;
    }

    /** Index where the current season starts within the rows (a gap of 100+ days marks an offseason); 0 if none. */
    private static int seasonStart(List<GameRow> rows) {
        for (int i = rows.size() - 1; i > 0; i--) {
            if (ChronoUnit.DAYS.between(rows.get(i - 1).date(), rows.get(i).date()) > 100) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Early-season evidence about a role change: {games this season, usage this season, minutes this season}, or
     * null when the window is a single season (no earlier games to compare with) or no game was played yet.
     */
    public double[] seasonSoFar(String playerId, LocalDate date) {
        List<GameRow> rows = lastRows(playerId, date);
        int s = seasonStart(rows);
        if (s == 0 || s >= rows.size()) {
            return null;
        }
        double um = 0, m = 0;
        for (int i = s; i < rows.size(); i++) {
            um += usage(rows.get(i)) * rows.get(i).minutes();
            m += rows.get(i).minutes();
        }
        int n = rows.size() - s;
        return new double[]{n, um / m, m / n};
    }

    /** Plain recency-weighted usage ignoring team changes: what a naive "recent form" view would assume. */
    public double plainUsage(String playerId, LocalDate date) {
        List<GameRow> rows = lastRows(playerId, date);
        double num = 0, den = 0;
        for (int i = 0; i < rows.size(); i++) {
            double w = Math.pow(0.5, (rows.size() - 1 - i) / 5.0) * rows.get(i).minutes();
            num += w * usage(rows.get(i));
            den += w;
        }
        return (num + PRIOR_USAGE * PRIOR_WEIGHT * 0.2) / (den + PRIOR_WEIGHT * 0.2);
    }
}
