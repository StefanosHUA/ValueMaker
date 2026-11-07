package com.fermabets.service;

import com.fermabets.AppClock;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Hit rates by probability and odds band, with a column per recent night so you know which levels to trust.
 */
@Service
public class LevelBook {
    public static final int NIGHTS = 7;
    public static final int MIN_TRUST = 8;
    static final double[] P_EDGES = {0.0, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 1.01};
    static final double[] O_EDGES = {1.0, 1.50, 1.80, 2.20, 3.00, 99.0};

    public record Cell(int n, int wins, double predSum) {
        static final Cell EMPTY = new Cell(0, 0, 0);

        Cell plus(boolean win, double p) {
            return new Cell(n + 1, wins + (win ? 1 : 0), predSum + p);
        }

        public double hit() {
            return n == 0 ? Double.NaN : wins / (double) n;
        }

        public double pred() {
            return n == 0 ? Double.NaN : predSum / n;
        }

        public String shortText() {
            return n == 0 ? "—" : wins + "/" + n;
        }
    }

    public record Row(String kind, String label, double lo, double hi, Cell season, List<Cell> nights) {
        public String trust() {
            return trustOf(season);
        }

        public String trustClass() {
            return switch (trust()) {
                case "εμπιστεύσου" -> "ok";
                case "υπερεκτιμά", "προσοχή" -> "bad";
                default -> "low";
            };
        }

        public boolean match(double v) {
            return v >= lo && v < hi;
        }
    }

    public record Combo(String pLabel, String oLabel, double pLo, double pHi, double oLo, double oHi, Cell season,
                        double pnl) {
        public double roi() {
            return season.n() == 0 ? Double.NaN : pnl / season.n();
        }

        public String roiText() {
            return season.n() == 0 ? "—" : String.format(Locale.US, "%+.0f%%", roi() * 100);
        }

        public String trust() {
            return trustOf(season);
        }

        public String trustClass() {
            return switch (trust()) {
                case "εμπιστεύσου" -> "ok";
                case "υπερεκτιμά", "προσοχή" -> "bad";
                default -> "low";
            };
        }

        public boolean match(double p, double odds) {
            return p >= pLo && p < pHi && odds >= oLo && odds < oHi;
        }

        public String headline() {
            String pair = pLabel + " × " + oLabel;
            return season.n() < MIN_TRUST ? "Πρώτος προς το παρόν (λίγα δείγματα): " + pair
                    : "Καλύτερος συνδυασμός: " + pair;
        }

        /** Higher is better. Needs ≥3 samples; overconfident cells are pushed down. */
        public double score() {
            if (season.n() < 3) {
                return Double.NEGATIVE_INFINITY;
            }
            double s = roi() + 0.015 * Math.min(season.n(), 20);
            if ("υπερεκτιμά".equals(trust())) {
                s -= 0.30;
            }
            return s;
        }
    }

    public record Report(List<LocalDate> nights, List<Row> probs, List<Row> odds, List<Combo> combos,
                         List<Combo> ranked, List<String> pLabels, List<String> oLabels) {
        public Combo best() {
            return ranked.isEmpty() ? null : ranked.get(0);
        }

        public String badge(double p) {
            return badge(p, Double.NaN);
        }

        public String badge(double p, double bookOdds) {
            if (!Double.isNaN(bookOdds)) {
                for (Combo c : combos) {
                    if (c.match(p, bookOdds)) {
                        if (c.season().n() == 0) {
                            return c.pLabel() + " × " + c.oLabel() + ": δεν έχει κλείσει ακόμα";
                        }
                        return String.format(Locale.US, "%s × %s: %s · ROI %s · %s", c.pLabel(), c.oLabel(),
                                c.season().shortText(), c.roiText(), c.trust());
                    }
                }
            }
            for (Row r : probs) {
                if (r.match(p)) {
                    if (r.season().n() == 0) {
                        return r.label() + ": δεν έχει κλείσει ακόμα βραδιά";
                    }
                    return String.format(Locale.US, "%s: %s (%.0f%% hit / %.0f%% pred) · %s", r.label(),
                            r.season().shortText(), r.season().hit() * 100, r.season().pred() * 100, r.trust());
                }
            }
            return "";
        }
    }

    private final DataRepository repo;

    public LevelBook(DataRepository repo) {
        this.repo = repo;
    }

    public Report report() {
        return report(AppClock.today());
    }

    /** {@code focus} is pinned as the first night column when that date has results (the day you are looking at). */
    public Report report(LocalDate focus) {
        List<Sample> samples = samples();
        TreeSet<LocalDate> dates = new TreeSet<>(Comparator.reverseOrder());
        for (Sample s : samples) {
            dates.add(s.date());
        }
        List<LocalDate> nights = new ArrayList<>();
        if (focus != null && dates.contains(focus)) {
            nights.add(focus);
        }
        for (LocalDate d : dates) {
            if (!nights.contains(d) && nights.size() < NIGHTS) {
                nights.add(d);
            }
        }
        List<Row> pRows = rows("p", P_EDGES, true, samples, nights);
        List<Row> oRows = rows("odds", O_EDGES, false, samples, nights);
        List<Combo> combos = combos(samples);
        List<Combo> ranked = combos.stream()
                .filter(c -> c.oHi() < 50 && c.score() > Double.NEGATIVE_INFINITY)
                .sorted(Comparator.comparingDouble(Combo::score).reversed()).toList();
        return new Report(nights, pRows, oRows, combos, ranked, pRows.stream().map(Row::label).toList(),
                oRows.stream().map(Row::label).toList());
    }

    static String trustOf(Cell c) {
        if (c.n() < MIN_TRUST) {
            return "λίγα δείγματα";
        }
        double d = c.hit() - c.pred();
        if (d >= -0.05 && d <= 0.08) {
            return "εμπιστεύσου";
        }
        if (d < -0.08) {
            return "υπερεκτιμά";
        }
        return "προσοχή";
    }

    static int band(double v, double[] edges) {
        for (int i = 0; i < edges.length - 1; i++) {
            if (v >= edges[i] && v < edges[i + 1]) {
                return i;
            }
        }
        return Math.max(0, edges.length - 2);
    }

    static String pLabel(double lo, double hi) {
        int a = (int) Math.round(lo * 100);
        int b = (int) Math.round(Math.min(hi, 1.0) * 100);
        return a + "–" + b + "%";
    }

    static String oLabel(double lo, double hi) {
        if (hi >= 50) {
            return String.format(Locale.US, "%.2f+", lo);
        }
        return String.format(Locale.US, "%.2f–%.2f", lo, hi);
    }

    private record Sample(LocalDate date, double p, double odds, boolean win) {
    }

    private List<Sample> samples() {
        Map<String, Sample> uniq = new LinkedHashMap<>();
        for (Map<String, Object> r : repo.jdbc().queryForList(
                "SELECT game_date, player_id, stat, line, side, p_final, COALESCE(close_odds, open_odds) AS odds, result"
                        + " FROM pick_log WHERE result IN ('WIN','LOSS') AND p_final IS NOT NULL AND"
                        + DataRepository.notExcludedSql("pick_log"))) {
            put(uniq, r, num(r.get("p_final")), num(r.get("odds")));
        }
        for (Map<String, Object> r : repo.jdbc().queryForList(
                "SELECT game_date, player_id, stat, line, side, model_prob, odds, result FROM bet"
                        + " WHERE result IN ('WIN','LOSS') AND model_prob IS NOT NULL AND"
                        + DataRepository.notExcludedSql("bet"))) {
            putIfAbsent(uniq, r, num(r.get("model_prob")), num(r.get("odds")));
        }
        return new ArrayList<>(uniq.values());
    }

    private static void put(Map<String, Sample> uniq, Map<String, Object> r, Double p, Double odds) {
        if (p == null || odds == null || odds <= 1.0) {
            return;
        }
        uniq.put(key(r), new Sample(LocalDate.parse((String) r.get("game_date")), p, odds,
                "WIN".equals(r.get("result"))));
    }

    private static void putIfAbsent(Map<String, Sample> uniq, Map<String, Object> r, Double p, Double odds) {
        if (p == null || odds == null || odds <= 1.0 || uniq.containsKey(key(r))) {
            return;
        }
        uniq.put(key(r), new Sample(LocalDate.parse((String) r.get("game_date")), p, odds,
                "WIN".equals(r.get("result"))));
    }

    private static String key(Map<String, Object> r) {
        return r.get("game_date") + "|" + r.get("player_id") + "|" + r.get("stat") + "|" + r.get("line") + "|"
                + r.get("side");
    }

    private static Double num(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }

    private static List<Row> rows(String kind, double[] edges, boolean pct, List<Sample> samples,
                                 List<LocalDate> nights) {
        int m = edges.length - 1;
        Cell[] season = new Cell[m];
        Cell[][] night = new Cell[m][nights.size()];
        for (int i = 0; i < m; i++) {
            season[i] = Cell.EMPTY;
            for (int j = 0; j < nights.size(); j++) {
                night[i][j] = Cell.EMPTY;
            }
        }
        for (Sample s : samples) {
            int i = band(pct ? s.p() : s.odds(), edges);
            season[i] = season[i].plus(s.win(), s.p());
            int j = nights.indexOf(s.date());
            if (j >= 0) {
                night[i][j] = night[i][j].plus(s.win(), s.p());
            }
        }
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            String label = pct ? pLabel(edges[i], edges[i + 1]) : oLabel(edges[i], edges[i + 1]);
            out.add(new Row(kind, label, edges[i], edges[i + 1], season[i], List.of(night[i])));
        }
        return out;
    }

    static List<Combo> combos(List<Sample> samples) {
        int pn = P_EDGES.length - 1, on = O_EDGES.length - 1;
        Cell[][] cells = new Cell[pn][on];
        double[][] pnl = new double[pn][on];
        for (int i = 0; i < pn; i++) {
            Arrays.fill(cells[i], Cell.EMPTY);
        }
        for (Sample s : samples) {
            int i = band(s.p(), P_EDGES);
            int j = band(s.odds(), O_EDGES);
            cells[i][j] = cells[i][j].plus(s.win(), s.p());
            pnl[i][j] += s.win() ? s.odds() - 1.0 : -1.0;
        }
        List<Combo> out = new ArrayList<>();
        for (int i = 0; i < pn; i++) {
            for (int j = 0; j < on; j++) {
                out.add(new Combo(pLabel(P_EDGES[i], P_EDGES[i + 1]), oLabel(O_EDGES[j], O_EDGES[j + 1]), P_EDGES[i],
                        P_EDGES[i + 1], O_EDGES[j], O_EDGES[j + 1], cells[i][j], pnl[i][j]));
            }
        }
        return out;
    }
}
