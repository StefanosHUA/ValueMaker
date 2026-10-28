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
                             List<String> notes) {
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

    public record Backtest(String league, Stat stat, int nTrain, int nTest, double rmseModel, double rmseNaive,
                           double brierModel, double brierNaive, List<Bin> bins, Map<String, Double> coefficients,
                           double dispersion) {
    }

    private final DataRepository repo;
    private final Map<String, LeagueData> data = new HashMap<>();
    private final Map<String, ProjectionModel> models = new HashMap<>();

    public ModelService(DataRepository repo) {
        this.repo = repo;
    }

    public synchronized void invalidate() {
        data.clear();
        models.clear();
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
                brM / n, brN / n, out, coef, m.dispersion());
    }
}
