package com.fermabets.model;

import java.util.*;

/**
 * Port of the Fermabets sheet model (props_model.py): credibility-weighted filters (55%) blended with per-game
 * similarity (45%), then Quarter-Kelly. Keep in sync with the Python version; ParityTest checks both agree.
 */
public final class FermabetsModel {
    public static final double BUHLMANN_K = 5.0;
    public static final double H2H_K = 2.0;
    public static final double BLEND_A = 0.55;
    public static final double BLEND_B = 0.45;
    public static final double KELLY_FRACTION = 0.25;
    public static final double MAX_STAKE_PCT = 0.05;

    private static final Map<String, Double> BASE = weights("mins", .30, "fga", .25, "fta", .10, "3pa", .10,
            "usg", .15, "pace", .10, "home", .10, "h2h", .10);
    private static final Map<String, Double> REB = weights("mins", .40, "pace", .25, "home", .20, "h2h", .15);
    private static final Map<String, Double> THREE = weights("3pa", .25, "mins", .20, "fga", .15, "volume", .15,
            "home", .15, "h2h", .10);
    private static final Map<String, Double> AST = weights("mins", .30, "pace", .20, "fga", .15, "usg", .15,
            "home", .10, "h2h", .10);
    private static final Map<String, List<String>> SIMILARITY = Map.of(
            "default", List.of("min", "fga", "fta", "3pa", "usg", "pace"),
            "reb", List.of("min", "pace"),
            "three", List.of("min", "fga", "3pa", "usg", "pace"),
            "ast", List.of("min", "fga", "usg", "pace"));
    private static final Set<String> TEAM_STOPWORDS = Set.of("bc", "b", "c", "basket", "basketball", "club", "kk",
            "sk", "as", "gs", "athens", "piraeus", "istanbul", "moscow", "munich", "milano", "milan", "the", "vs", "at");

    private FermabetsModel() {
    }

    /** One sheet input: a floor (high null), an inclusive range, or one exact value. */
    public record Estimate(double low, Double high, boolean exact) {
        public static Estimate floor(double low) {
            return new Estimate(low, null, false);
        }

        public static Estimate range(double low, double high) {
            return new Estimate(Math.min(low, high), Math.max(low, high), false);
        }

        public static Estimate exactly(double v) {
            return new Estimate(v, v, true);
        }

        public String label() {
            if (exact) {
                return "=" + num(low);
            }
            return high == null ? ">=" + num(low) : num(low) + "-" + num(high);
        }

        private static String num(double v) {
            String s = String.format(Locale.US, "%.4f", v).replaceAll("0+$", "");
            return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
        }
    }

    /** One game of the log. NaN marks a missing value (e.g. no pace). */
    public record Game(double value, double min, double fga, double fta, double fg3a, double usg, double pace,
                       Boolean home, String opp) {
        double col(String c) {
            return switch (c) {
                case "min" -> min;
                case "fga" -> fga;
                case "fta" -> fta;
                case "3pa" -> fg3a;
                case "usg" -> usg;
                case "pace" -> pace;
                default -> throw new IllegalArgumentException(c);
            };
        }
    }

    /** stat: canonical name (pts, trb, ast, 3pm, ...). h2hTeam: opponent whose games count only as H2H. */
    public record Inputs(String stat, double line, Estimate mins, Estimate fga, Estimate fta, Estimate usg,
                         Estimate pace, Estimate fg3a, Boolean home, String h2hTeam) {
        Estimate of(String c) {
            return switch (c) {
                case "min", "mins" -> mins;
                case "fga" -> fga;
                case "fta" -> fta;
                case "3pa" -> fg3a;
                case "usg" -> usg;
                case "pace" -> pace;
                default -> null;
            };
        }
    }

    public record Factor(String name, int n, double z, double baseWeight, double effectiveWeight, Double hitRate,
                         String band) {
    }

    public record Stake(double ev, double implied, double kellyFull, double kellyQuarter, double stake,
                        boolean capped, String status) {
    }

    public record Result(double prob, Double pFilters, Double pSimilarity, List<Factor> factors) {
    }

    public static String family(String stat) {
        return switch (stat) {
            case "trb", "orb", "drb" -> "reb";
            case "3pm", "3pa" -> "three";
            case "ast" -> "ast";
            default -> "default";
        };
    }

    static Map<String, Double> baseWeights(String stat) {
        return switch (stat) {
            case "trb", "orb", "drb" -> REB;
            case "3pm", "3pa" -> THREE;
            case "ast" -> AST;
            default -> BASE;
        };
    }

    /** Drop missing factors and re-normalize the rest to 1.0. */
    static LinkedHashMap<String, Double> activeWeights(Inputs in) {
        LinkedHashMap<String, Double> raw = new LinkedHashMap<>(baseWeights(in.stat()));
        boolean pts = in.stat().equals("pts");
        String fam = family(in.stat());
        if (raw.containsKey("mins") && in.mins() == null) {
            raw.put("mins", 0.0);
        }
        if (raw.containsKey("fga") && in.fga() == null) {
            raw.put("fga", 0.0);
        }
        if (raw.containsKey("3pa") && in.fg3a() == null) {
            raw.put("3pa", 0.0);
        } else if (raw.containsKey("3pa") && !pts && !fam.equals("three")) {
            raw.put("3pa", 0.0);
        }
        if (raw.containsKey("pace") && in.pace() == null) {
            raw.put("pace", 0.0);
        }
        if (raw.containsKey("fta") && (in.fta() == null || !pts)) {
            raw.put("fta", 0.0);
        }
        if (raw.containsKey("usg") && in.usg() == null) {
            raw.put("usg", 0.0);
        }
        if (raw.containsKey("home") && in.home() == null) {
            raw.put("home", 0.0);
        }
        if (raw.containsKey("h2h") && (in.h2hTeam() == null || in.h2hTeam().isBlank())) {
            raw.put("h2h", 0.0);
        }
        if (raw.containsKey("volume") && in.usg() == null && in.pace() == null) {
            raw.put("volume", 0.0);
        }
        double total = raw.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) {
            throw new IllegalArgumentException("No active indicators: all expected inputs are blank.");
        }
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v > 0) {
                out.put(k, v / total);
            }
        });
        return out;
    }

    public static double credibility(int n, double k) {
        return n + k == 0 ? 0.0 : n / (n + k);
    }

    static boolean inside(double v, Estimate e) {
        if (Double.isNaN(v)) {
            return false;
        }
        if (e.exact()) {
            return Math.abs(v - e.low()) <= 1e-4;
        }
        return v >= e.low() && (e.high() == null || v <= e.high());
    }

    private static boolean mask(Game g, Inputs in, String factor) {
        return switch (factor) {
            case "mins" -> inside(g.min(), in.mins());
            case "fga" -> inside(g.fga(), in.fga());
            case "3pa" -> inside(g.fg3a(), in.fg3a());
            case "pace" -> inside(g.pace(), in.pace());
            case "fta" -> inside(g.fta(), in.fta());
            case "usg" -> inside(g.usg(), in.usg());
            case "volume" -> (in.usg() == null || inside(g.usg(), in.usg()))
                    && (in.pace() == null || inside(g.pace(), in.pace()));
            case "home" -> in.home() != null && in.home().equals(g.home());
            default -> throw new IllegalArgumentException(factor);
        };
    }

    private static String band(Inputs in, String factor) {
        if (factor.equals("volume")) {
            List<String> parts = new ArrayList<>();
            if (in.usg() != null) {
                parts.add("usg " + in.usg().label());
            }
            if (in.pace() != null) {
                parts.add("pace " + in.pace().label());
            }
            return String.join(", ", parts);
        }
        Estimate e = in.of(factor);
        return e == null ? "" : e.label();
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+]", "");
    }

    private static Set<String> tokens(String name) {
        Set<String> out = new HashSet<>();
        for (String t : (name == null ? "" : name.toLowerCase(Locale.ROOT)).split("[^a-z0-9]+")) {
            if (t.length() >= 3 && !TEAM_STOPWORDS.contains(t)) {
                out.add(t);
            }
        }
        String raw = norm(name);
        if (!raw.isEmpty()) {
            out.add(raw);
        }
        return out;
    }

    static boolean isOpponent(String opp, String team) {
        String o = norm(opp), t = norm(team);
        if (o.isEmpty()) {
            return false;
        }
        if (!t.isEmpty() && (o.contains(t) || t.contains(o))) {
            return true;
        }
        Set<String> a = tokens(team);
        a.retainAll(tokens(opp));
        return !a.isEmpty();
    }

    /** Filter layer: hit rates on each band, shrunk with Z = N/(N+K), H2H capped at its listed weight. */
    static Map.Entry<Double, List<Factor>> filters(List<Game> games, Inputs in) {
        LinkedHashMap<String, Double> bases = activeWeights(in);
        String team = in.h2hTeam() == null || in.h2hTeam().isBlank() ? null : in.h2hTeam();
        List<Game> rest = new ArrayList<>(), h2h = new ArrayList<>();
        for (Game g : games) {
            (team != null && isOpponent(g.opp(), team) ? h2h : rest).add(g);
        }
        List<Factor> factors = new ArrayList<>();
        double restRaw = 0, restP = 0;
        for (var e : bases.entrySet()) {
            String name = e.getKey();
            if (name.equals("h2h")) {
                continue;
            }
            int n = 0, hits = 0;
            for (Game g : rest) {
                if (mask(g, in, name)) {
                    n++;
                    hits += g.value() > in.line() ? 1 : 0;
                }
            }
            double z = credibility(n, BUHLMANN_K);
            Double hit = n > 0 ? (double) hits / n : null;
            double eff = e.getValue() * z;
            String label = name.equals("home") ? (Boolean.TRUE.equals(in.home()) ? "home" : "away") : name;
            factors.add(new Factor(label, n, z, e.getValue(), eff, hit, band(in, name)));
            restRaw += eff;
            if (hit != null) {
                restP += eff * hit;
            }
        }
        Double pRest = restRaw > 0 ? restP / restRaw : null;
        if (bases.containsKey("h2h") && !h2h.isEmpty()) {
            int n = h2h.size();
            long hits = h2h.stream().filter(g -> g.value() > in.line()).count();
            double rate = (double) hits / n;
            double zH = credibility(n, H2H_K);
            if (pRest != null && zH > 0) {
                double share = Math.min(zH, bases.get("h2h"));
                List<Factor> out = new ArrayList<>();
                for (Factor f : factors) {
                    out.add(new Factor(f.name(), f.n(), f.z(), f.baseWeight(),
                            (1 - share) * f.effectiveWeight() / restRaw, f.hitRate(), f.band()));
                }
                out.add(new Factor("h2h", n, zH, bases.get("h2h"), share, rate, "vs " + team));
                return Map.entry((1 - share) * pRest + share * rate, out);
            }
            if (zH > 0) {
                factors.add(new Factor("h2h", n, zH, bases.get("h2h"), zH, rate, "vs " + team));
                return Map.entry(rate, factors);
            }
        }
        if (restRaw <= 0) {
            return new AbstractMap.SimpleEntry<>(null, factors);
        }
        List<Factor> out = new ArrayList<>();
        for (Factor f : factors) {
            out.add(new Factor(f.name(), f.n(), f.z(), f.baseWeight(), f.effectiveWeight() / restRaw, f.hitRate(),
                    f.band()));
        }
        return Map.entry(pRest, out);
    }

    /** Similarity layer: one weight per game from how close it sits to every band. H2H games are left out. */
    static Double similarity(List<Game> games, Inputs in) {
        String team = in.h2hTeam() == null || in.h2hTeam().isBlank() ? null : in.h2hTeam();
        List<Game> work = games.stream().filter(g -> team == null || !isOpponent(g.opp(), team)).toList();
        if (work.isEmpty()) {
            return null;
        }
        String fam = family(in.stat());
        boolean pts = in.stat().equals("pts");
        List<String> allowed = SIMILARITY.getOrDefault(fam, SIMILARITY.get("default"));
        double[] w = new double[work.size()];
        Arrays.fill(w, 1.0);
        for (String col : List.of("min", "fga", "fta", "3pa", "usg", "pace")) {
            if (!allowed.contains(col)) {
                continue;
            }
            Estimate e = in.of(col);
            if (col.equals("fta") && !pts) {
                e = null;
            }
            if (col.equals("3pa") && !pts && !fam.equals("three")) {
                e = null;
            }
            if (e == null) {
                continue;
            }
            double[] v = work.stream().mapToDouble(g -> g.col(col)).toArray();
            double[] k = kernel(v, e);
            for (int i = 0; i < w.length; i++) {
                w[i] *= k[i];
            }
        }
        if (in.home() != null) {
            for (int i = 0; i < w.length; i++) {
                w[i] *= in.home().equals(work.get(i).home()) ? 1.15 : 0.35;
            }
        }
        double total = 0, hit = 0;
        for (int i = 0; i < w.length; i++) {
            total += w[i];
            hit += work.get(i).value() > in.line() ? w[i] : 0;
        }
        return total <= 0 ? null : hit / total;
    }

    static double[] kernel(double[] v, Estimate e) {
        int n = v.length;
        double[] out = new double[n];
        if (e.exact()) {
            for (int i = 0; i < n; i++) {
                out[i] = !Double.isNaN(v[i]) && Math.abs(v[i] - e.low()) <= 1e-4 ? 1.0 : 0.0;
            }
            return out;
        }
        double[] valid = Arrays.stream(v).filter(x -> !Double.isNaN(x)).sorted().toArray();
        double median = valid.length == 0 ? Double.NaN : valid.length % 2 == 1 ? valid[valid.length / 2]
                : (valid[valid.length / 2 - 1] + valid[valid.length / 2]) / 2.0;
        double std = Double.NaN;
        if (valid.length > 1) {
            double mean = Arrays.stream(valid).average().orElse(0);
            double ss = Arrays.stream(valid).map(x -> (x - mean) * (x - mean)).sum();
            std = Math.sqrt(ss / (valid.length - 1));
        }
        boolean useStd = !Double.isNaN(std) && std > 1e-9;
        if (e.high() == null) {
            double target = e.low();
            double scale = useStd ? std : Math.max(Math.abs(target), 1.0) * 0.25;
            for (int i = 0; i < n; i++) {
                double f = Double.isNaN(v[i]) ? median : v[i];
                double z = Math.max(-8, Math.min(8, (f - target) / scale));
                double k = Math.exp(-0.5 * z * z);
                boolean below = !Double.isNaN(v[i]) && v[i] < target;
                out[i] = below ? k * 0.15 : Math.max(k, 0.55);
            }
            return out;
        }
        double low = e.low(), high = e.high();
        double scale = useStd ? std : Math.max(high - low, 1.0) * 0.5;
        for (int i = 0; i < n; i++) {
            double f = Double.isNaN(v[i]) ? median : v[i];
            double dist = f < low ? low - f : f > high ? f - high : 0.0;
            double z = Math.max(0, Math.min(8, dist / scale));
            double k = Math.exp(-0.5 * z * z);
            boolean outside = Double.isNaN(v[i]) || v[i] < low || v[i] > high;
            out[i] = outside ? k * 0.15 : Math.max(k, 1.0);
        }
        return out;
    }

    public static Result evaluate(List<Game> games, Inputs in) {
        if (games.isEmpty()) {
            throw new IllegalArgumentException("No games with a value for " + in.stat() + ".");
        }
        var a = filters(games, in);
        Double pA = a.getKey();
        Double pB = similarity(games, in);
        double p;
        if (pA == null && pB == null) {
            throw new IllegalArgumentException("Both model layers failed.");
        } else if (pA == null) {
            p = pB;
        } else if (pB == null) {
            p = pA;
        } else {
            p = BLEND_A * pA + BLEND_B * pB;
        }
        return new Result(Math.min(1.0, Math.max(0.0, p)), pA, pB, a.getValue());
    }

    public static Stake stake(double p, double odds, double bankroll) {
        if (odds <= 1.0) {
            throw new IllegalArgumentException("Odds must be > 1.0");
        }
        double implied = 1.0 / odds;
        double ev = p * odds - 1.0;
        double cap = Math.round(bankroll * MAX_STAKE_PCT * 100.0) / 100.0;
        if (ev <= 0) {
            return new Stake(ev, implied, 0, 0, 0, false, "NO BET");
        }
        double full = Math.max(0.0, Math.min(1.0, ev / (odds - 1.0)));
        double quarter = KELLY_FRACTION * full;
        double stake = bankroll * quarter;
        boolean capped = stake > cap;
        if (capped) {
            stake = cap;
        }
        return new Stake(ev, implied, full, quarter, Math.round(stake * 100.0) / 100.0, capped, "BET");
    }

    private static Map<String, Double> weights(Object... kv) {
        LinkedHashMap<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Double) kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }
}
