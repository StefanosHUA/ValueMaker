package com.fermabets.service;

import com.fermabets.domain.GameRow;
import com.fermabets.domain.Stat;
import com.fermabets.model.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class ModelService {
    /** Share of the final probability that comes from the regression/distribution model. */
    static final double DIST_WEIGHT = 0.7;

    public record Prediction(String league, String playerId, String playerName, Stat stat, String team, String opp,
                             boolean home, LocalDate date, double mean, double projMinutes, int gamesUsed,
                             double[] features, CountDist dist, List<GameRow> history,
                             List<String> notes) implements PickInfo {
        /** Projected possessions-used share while on the floor (20 = league average). */
        public double usage() {
            return features[11];
        }

        /** What a naive recent-form view would assume for usage. */
        public double formUsage() {
            return features[11] - features[16];
        }

        public double roleMinutes() {
            return features[13];
        }

        public double continuity() {
            return features[15];
        }

        /** Expected margin for the player's team (positive = favourite), bookmaker based when available. */
        public double margin() {
            return features[23] * 10.0;
        }
    }

    public record PlayerView(String id, String name, double minutes, double usage, double formUsage, double continuity,
                             boolean out, boolean assumedOut, Map<Stat, Double> projection) {
    }

    public record Probs(double over, double under, double push, double distOver, Double baseOver) {
    }

    public record Bin(double lo, double hi, int n, double predicted, double actual) {
    }

    public record StatCheck(Stat stat, String label, int n, double rmseSeason, double rmseLast5, double rmseCombo,
                            double brierSeason, double brierLast5, double brierCombo, double weight) {
        public boolean comboBetter() {
            return brierCombo < brierSeason && brierCombo < brierLast5;
        }
    }

    public record Backtest(String league, Stat stat, int nTrain, int nTest, double rmseModel, double rmseNaive,
                           double brierModel, double brierNaive, List<Bin> bins, Map<String, Double> coefficients,
                           double dispersion, int nProj, double rmseSeason, double rmseLast5, double brierSeason,
                           double brierLast5, double blendWeight, List<StatCheck> projections) {
    }

    private final DataRepository repo;
    private final Map<String, LeagueData> data = new HashMap<>();
    private final Map<String, ProjectionModel> models = new HashMap<>();
    private final Map<String, Double> last5Weights = new HashMap<>();

    public ModelService(DataRepository repo) {
        this.repo = repo;
    }

    public synchronized void invalidate() {
        data.clear();
        models.clear();
        last5Weights.clear();
    }

    /** Last-5 share learned from games before {@code before}. Used by NBA picks and the backtest combo. */
    public synchronized double last5Weight(String league, LocalDate before) {
        return last5Weights.computeIfAbsent(league + "|" + before, k -> fitLast5Weight(data(league), null, before));
    }

    public synchronized LeagueData data(String league) {
        return data.computeIfAbsent(league, repo::loadLeague);
    }

    public synchronized ProjectionModel model(String league, Stat stat) {
        return models.computeIfAbsent(league + "|" + stat,
                k -> ProjectionModel.train(stat, FeatureBuilder.samples(data(league), stat, null)));
    }

    public Optional<Prediction> predict(String league, String playerId, String playerName, Stat stat,
                                        LocalDate date) {
        LeagueData d = data(league);
        List<GameRow> history = d.history(playerId);
        if (history.size() < FeatureBuilder.MIN_PRIOR_GAMES) {
            return Optional.empty();
        }
        String team = history.get(history.size() - 1).team();
        var fixture = repo.fixtureFor(league, date, team);
        if (fixture.isEmpty()) {
            return Optional.empty();
        }
        boolean home = fixture.get().home().equals(team);
        String opp = home ? fixture.get().away() : fixture.get().home();
        Set<String> absent = repo.absentees(date);
        List<String> crew = repo.crew(league, date, fixture.get().home());
        var market = repo.gameMarket(league, date, fixture.get().home(), fixture.get().away());
        Double mktMargin = market.map(g -> home ? g.margin() : -g.margin()).orElse(null);
        Double mktTotal = market.map(DataRepository.GameMarket::total).orElse(null);
        FeatureBuilder.Ctx ctx = new FeatureBuilder.Ctx(team, opp, date, home, null, absent, crew, mktMargin,
                mktTotal);
        double[] x = FeatureBuilder.build(d, history, stat, ctx);
        if (x == null) {
            return Optional.empty();
        }
        ProjectionModel m = model(league, stat);
        List<String> notes = new ArrayList<>();
        if (x[4] == 1) {
            notes.add("back-to-back");
        }
        if (x[8] > 0) {
            notes.add(String.format("%.0f rotation minutes missing", x[8]));
        }
        if (Math.abs(x[16]) >= 2.5) {
            notes.add(String.format("usage %.1f%% vs %.1f%% on older form (%s role)", x[11], x[11] - x[16],
                    x[16] < 0 ? "smaller" : "bigger"));
        }
        double mg = x[23] * 10.0;
        String src = mktMargin != null ? "bookmaker" : "our ratings";
        if (Math.abs(mg) >= 9.0) {
            notes.add(String.format("blowout risk: %s by %.0f (%s)%s", mg > 0 ? "favoured" : "underdog", Math.abs(mg), src,
                    mg > 0 ? " - starters may sit late" : " - garbage-time minutes possible"));
        } else if (Math.abs(mg) <= 3.0) {
            notes.add(String.format("tight game (%+.1f, %s) - starters play heavy minutes", mg, src));
        }
        if (!crew.isEmpty()) {
            notes.add(String.format("crew whistle %+.1f%%", x[10] * 100));
        }
        return Optional.of(new Prediction(league, playerId, playerName, stat, team, opp, home, date,
                m.mean(x), x[1], history.size(), x, m.dist(x), history, notes));
    }

    /** Projected roles and stat lines of every rostered player of a team (for the match page). */
    public List<PlayerView> teamView(String league, String team, LocalDate date) {
        LeagueData d = data(league);
        LeagueData.Role role = d.upcomingRole(team, date, repo.absentees(date), null);
        Map<String, String> names = new HashMap<>();
        repo.players(league).forEach(p -> names.put(p.id(), p.name()));
        List<PlayerView> out = new ArrayList<>();
        for (var e : role.players().entrySet()) {
            String id = e.getKey();
            boolean out0 = role.absent().contains(id);
            double[] v = e.getValue();
            if (!out0 && v[2] < 8.0) {
                continue;
            }
            if (out0 && d.lastRows(id, date).stream().mapToDouble(GameRow::minutes).average().orElse(0) < 15) {
                continue;
            }
            Map<Stat, Double> proj = new EnumMap<>(Stat.class);
            if (!out0) {
                for (Stat s : List.of(Stat.PTS, Stat.REB, Stat.AST, Stat.FG3M)) {
                    try {
                        predict(league, id, names.getOrDefault(id, id), s, date)
                                .ifPresent(p -> proj.put(s, p.mean()));
                    } catch (IllegalStateException ignored) {
                        // not enough data to train this stat
                    }
                }
            }
            out.add(new PlayerView(id, names.getOrDefault(id, id), v[2], v[1], d.plainUsage(id, date), v[3], out0,
                    role.assumedOut().contains(id), proj));
        }
        out.sort(Comparator.comparing(PlayerView::out).thenComparing(Comparator.comparingDouble(PlayerView::minutes).reversed()));
        return out;
    }

    public Probs probs(Prediction p, double line) {
        double dOver = p.dist().over(line);
        double dUnder = p.dist().under(line);
        var base = BaselineModel.probs(p.history(), p.stat(), line, p.home(), p.opp(), p.projMinutes());
        double over = dOver, under = dUnder;
        Double baseOver = null;
        if (base != null) {
            over = DIST_WEIGHT * dOver + (1 - DIST_WEIGHT) * base.over();
            under = DIST_WEIGHT * dUnder + (1 - DIST_WEIGHT) * base.under();
            baseOver = base.over();
        }
        return new Probs(over, under, p.dist().push(line), dOver, baseOver);
    }

    /** Walk-forward check: train on the first 75% of dates, test on the rest against half-point lines. */
    public Backtest backtest(String league, Stat stat) {
        LeagueData d = data(league);
        List<FeatureBuilder.Sample> all = new ArrayList<>(FeatureBuilder.samples(d, stat, null));
        all.sort(Comparator.comparing(FeatureBuilder.Sample::date));
        if (all.size() < 400) {
            throw new IllegalStateException("Not enough data for a backtest (" + all.size() + " samples).");
        }
        LocalDate cutoff = all.get((int) (all.size() * 0.75)).date();
        List<FeatureBuilder.Sample> train = all.stream().filter(s -> s.date().isBefore(cutoff)).toList();
        List<FeatureBuilder.Sample> test = all.stream()
                .filter(s -> !s.date().isBefore(cutoff) && s.ewmaMin() >= 20.0).toList();
        ProjectionModel m = ProjectionModel.train(stat, train);
        double seM = 0, seN = 0, brM = 0, brN = 0;
        double[][] bins = new double[10][3];
        for (var s : test) {
            double mu = m.mean(s.x());
            seM += (s.y() - mu) * (s.y() - mu);
            seN += (s.y() - s.ewma()) * (s.y() - s.ewma());
            double line = Math.floor(s.ewma()) + 0.5;
            double pm = m.dist(s.x()).over(line);
            double pn = m.naive(Math.max(0.05, s.ewma())).over(line);
            double hit = s.y() > line ? 1 : 0;
            brM += (pm - hit) * (pm - hit);
            brN += (pn - hit) * (pn - hit);
            int b = Math.min(9, (int) (pm * 10));
            bins[b][0]++;
            bins[b][1] += pm;
            bins[b][2] += hit;
        }
        List<StatCheck> projections = last5ByStat(d, cutoff, m.dispersion());
        StatCheck selected = projections.stream().filter(p -> p.stat() == stat).findFirst()
                .orElse(new StatCheck(stat, stat.label(), 0, 0, 0, 0, 0, 0, 0, Last5Form.BLEND));
        int n = Math.max(1, test.size());
        List<Bin> out = new ArrayList<>();
        for (int b = 0; b < 10; b++) {
            if (bins[b][0] > 0) {
                out.add(new Bin(b / 10.0, (b + 1) / 10.0, (int) bins[b][0], bins[b][1] / bins[b][0],
                        bins[b][2] / bins[b][0]));
            }
        }
        Map<String, Double> coef = new LinkedHashMap<>();
        for (int i = 0; i < FeatureBuilder.NAMES.length; i++) {
            coef.put(FeatureBuilder.NAMES[i], m.coefficient(i));
        }
        return new Backtest(league, stat, train.size(), test.size(), Math.sqrt(seM / n), Math.sqrt(seN / n),
                brM / n, brN / n, out, coef, m.dispersion(), selected.n(), selected.rmseSeason(),
                selected.rmseLast5(), selected.brierSeason(), selected.brierLast5(), selected.weight(),
                projections);
    }

    private double fitLast5Weight(LeagueData d, LocalDate from, LocalDate before) {
        double[] acc = new double[2];
        walkSeasonGames(d, from, before, (season, actual, prior, seasonP, last5P) -> {
            if (season.size() <= Last5Form.GAMES) {
                return;
            }
            for (Stat st : Last5Form.FIT_STATS) {
                Last5Form.acc(acc, Last5Form.projectedStat(season, st, seasonP.minutes(), 0.0),
                        Last5Form.projectedStat(season, st, last5P.minutes(), 1.0), st.of(actual),
                        Last5Form.scale(st));
            }
        });
        return Last5Form.fitWeight(acc[0], acc[1]);
    }

    /** Fit combo weight on dates before cutoff, score season / last-5 / combo on the rest. */
    private List<StatCheck> last5ByStat(LeagueData d, LocalDate cutoff, double k) {
        Stat[] stats = Stat.values();
        int ns = stats.length;
        double[] xx = new double[ns], xy = new double[ns];
        walkSeasonGames(d, null, cutoff, (season, actual, prior, seasonP, last5P) -> {
            if (season.size() <= Last5Form.GAMES) {
                return;
            }
            for (int si = 0; si < ns; si++) {
                Stat st = stats[si];
                double[] a = {xx[si], xy[si]};
                Last5Form.acc(a, Last5Form.projectedStat(season, st, seasonP.minutes(), 0.0),
                        Last5Form.projectedStat(season, st, last5P.minutes(), 1.0), st.of(actual),
                        Last5Form.scale(st));
                xx[si] = a[0];
                xy[si] = a[1];
            }
        });
        double[] w = new double[ns];
        for (int si = 0; si < ns; si++) {
            w[si] = Last5Form.fitWeight(xx[si], xy[si]);
        }
        double[] seS = new double[ns], seL = new double[ns], seC = new double[ns];
        double[] brS = new double[ns], brL = new double[ns], brC = new double[ns];
        int[] n = new int[ns];
        walkSeasonGames(d, cutoff, null, (season, actual, prior, seasonP, last5P) -> {
            for (int si = 0; si < ns; si++) {
                Stat st = stats[si];
                double y = st.of(actual);
                double line = Math.floor(FeatureBuilder.ewma(prior, st, false)) + 0.5;
                double muS = Last5Form.projectedStat(season, st, seasonP.minutes(), 0.0);
                double muL = Last5Form.projectedStat(season, st, last5P.minutes(), 1.0);
                double muC = Last5Form.blend(muS, muL, Last5Form.mix(season.size(), w[si]));
                seS[si] += (y - muS) * (y - muS);
                seL[si] += (y - muL) * (y - muL);
                seC[si] += (y - muC) * (y - muC);
                double hit = y > line ? 1 : 0;
                brS[si] += Math.pow(new CountDist(muS, k).over(line) - hit, 2);
                brL[si] += Math.pow(new CountDist(muL, k).over(line) - hit, 2);
                brC[si] += Math.pow(new CountDist(muC, k).over(line) - hit, 2);
                n[si]++;
            }
        });
        List<StatCheck> out = new ArrayList<>();
        for (int si = 0; si < ns; si++) {
            int np = Math.max(1, n[si]);
            out.add(new StatCheck(stats[si], stats[si].label(), n[si], Math.sqrt(seS[si] / np),
                    Math.sqrt(seL[si] / np), Math.sqrt(seC[si] / np), brS[si] / np, brL[si] / np, brC[si] / np,
                    w[si]));
        }
        return out;
    }

    private interface SeasonGame {
        void accept(List<GameRow> season, GameRow actual, List<GameRow> prior, ProjectionBuilder.Projection seasonP,
                    ProjectionBuilder.Projection last5P);
    }

    private void walkSeasonGames(LeagueData d, LocalDate from, LocalDate before, SeasonGame fn) {
        Map<LocalDate, LocalDate> seasonStart = new HashMap<>();
        for (List<GameRow> rows : d.byPlayer().values()) {
            for (int i = FeatureBuilder.MIN_PRIOR_GAMES; i < rows.size(); i++) {
                GameRow r = rows.get(i);
                if (from != null && r.date().isBefore(from)) {
                    continue;
                }
                if (before != null && !r.date().isBefore(before)) {
                    continue;
                }
                List<GameRow> prior = rows.subList(0, i);
                if (FeatureBuilder.ewma(prior, Stat.PTS, true) < 20.0) {
                    continue;
                }
                LocalDate start = seasonStart.computeIfAbsent(r.date(), d::currentSeasonStart);
                List<GameRow> season = new ArrayList<>();
                for (GameRow p : prior) {
                    if (!p.date().isBefore(start)) {
                        season.add(p);
                    }
                }
                if (season.size() < ProjectionBuilder.MIN_SEASON_GAMES) {
                    continue;
                }
                double[] usg = new double[season.size()];
                for (int j = 0; j < season.size(); j++) {
                    usg[j] = d.usage(season.get(j));
                }
                var seasonP = ProjectionBuilder.build(season, usg, Double.NaN, Double.NaN, 0.0);
                var last5P = ProjectionBuilder.build(season, usg, Double.NaN, Double.NaN, 1.0);
                if (seasonP.ready() && last5P.ready()) {
                    fn.accept(season, r, prior, seasonP, last5P);
                }
            }
        }
    }
}
