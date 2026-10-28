package com.fermabets.service;

import com.fermabets.domain.GameRow;
import com.fermabets.domain.Stat;
import com.fermabets.model.FermabetsModel;
import com.fermabets.model.FermabetsModel.Estimate;
import com.fermabets.model.LeagueData;
import com.fermabets.model.ProjectionBuilder;
import com.fermabets.model.ProjectionBuilder.Projection;
import com.fermabets.service.ModelService.Prediction;
import com.fermabets.service.ModelService.Probs;
import com.fermabets.service.OddsService.OddsRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ValueEngine {
    static final int MIN_GAMES = 15;
    static final double MIN_PROJ_MINUTES = 12.0;
    static final double MIN_ODDS = 1.40;
    static final double MAX_ODDS = 4.00;
    /** A model/market gap larger than this usually means information the model lacks (injury, role change). */
    static final double SUSPICIOUS_GAP = 0.15;
    static final double SINGLE_SIDED_MARGIN = 0.95;
    static final String FERMABETS_LEAGUE = "NBA";

    public record ValueBet(OddsRow odds, String side, double line, double bookOdds, double pModel, double pFair,
                           double pFinal, double ev, double stake, double score, boolean suspicious,
                           PickInfo prediction, String explanation, List<String> warnings) {
        public boolean fermabets() {
            return prediction instanceof FermabetsPick;
        }
    }

    /** Player context shared by all of his lines on the day. */
    private record NbaContext(String team, String opp, boolean home, List<GameRow> history, double[] usage,
                              double[] pace, Projection projection, List<String> warnings) {
    }

    private final OddsService oddsService;
    private final ModelService models;
    private final SettingsService settings;
    private final DataRepository repo;
    private final WarningService warningService;
    private final Map<LocalDate, List<String>> skipped = new ConcurrentHashMap<>();

    private volatile double calibration = 0.0;

    /** Probability shift learned from settled bets (negative when the model has been overconfident). */
    public void setCalibration(double shift) {
        this.calibration = shift;
    }

    public ValueEngine(OddsService oddsService, ModelService models, SettingsService settings, DataRepository repo,
                       WarningService warningService) {
        this.oddsService = oddsService;
        this.models = models;
        this.settings = settings;
        this.repo = repo;
        this.warningService = warningService;
    }

    /** NBA players left out of the last evaluation for the date, with the reason (e.g. too few games this season). */
    public List<String> skipped(LocalDate date) {
        return skipped.getOrDefault(date, List.of());
    }

    /** All positive-EV candidates for the day, best first, one per player+stat (best price/line shopping). */
    public List<ValueBet> evaluate(LocalDate date) {
        Map<String, Optional<Prediction>> cache = new HashMap<>();
        Map<String, Optional<NbaContext>> nbaCache = new HashMap<>();
        Set<String> skippedNow = new TreeSet<>();
        Map<String, ValueBet> best = new HashMap<>();
        for (OddsRow row : oddsService.forDate(date)) {
            String key = row.playerId() + "|" + row.stat();
            if (FERMABETS_LEAGUE.equals(row.league())) {
                Optional<NbaContext> ctx = nbaCache.computeIfAbsent(row.playerId(), k -> nbaContext(row, date));
                if (ctx.isEmpty()) {
                    continue;
                }
                if (!ctx.get().projection().ready()) {
                    skippedNow.add(String.format("%s: λίγοι φετινοί αγώνες (%d, χρειάζονται %d), δεν βγαίνει πρόταση.",
                            row.playerName(), ctx.get().projection().games(), ProjectionBuilder.MIN_SEASON_GAMES));
                    continue;
                }
                ValueBet bet = priceNba(row, ctx.get());
                if (bet != null) {
                    best.merge(key, bet, (a, b) -> a.score() >= b.score() ? a : b);
                }
                continue;
            }
            Optional<Prediction> pred;
            try {
                pred = cache.computeIfAbsent(key,
                        k -> models.predict(row.league(), row.playerId(), row.playerName(), row.stat(), date));
            } catch (IllegalStateException e) {
                continue;
            }
            if (pred.isEmpty() || pred.get().gamesUsed() < MIN_GAMES
                    || pred.get().projMinutes() < MIN_PROJ_MINUTES) {
                continue;
            }
            Probs probs = models.probs(pred.get(), row.line());
            for (String side : List.of("OVER", "UNDER")) {
                ValueBet bet = price(row, side, pred.get(), probs);
                if (bet == null) {
                    continue;
                }
                best.merge(key, bet, (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        skipped.put(date, List.copyOf(skippedNow));
        return best.values().stream().sorted(Comparator.comparingDouble(ValueBet::score).reversed()).toList();
    }

    /** Candidates for one fixture only. */
    public List<ValueBet> evaluateGame(LocalDate date, String league, String home, String away) {
        return evaluate(date).stream().filter(v -> v.odds().league().equals(league)
                && (v.prediction().team().equals(home) || v.prediction().team().equals(away))).toList();
    }

    /** Top picks: positive EV above the threshold, at most two per game. Fermabets picks need EV > 0 and a stake. */
    public List<ValueBet> top(List<ValueBet> all, int n) {
        List<ValueBet> out = new ArrayList<>();
        Map<String, Integer> perGame = new HashMap<>();
        for (ValueBet v : all) {
            boolean noValue = v.fermabets() ? v.ev() <= 0 : v.ev() < settings.minEv();
            if (noValue || v.stake() <= 0) {
                continue;
            }
            String game = v.prediction().home() ? v.prediction().team() : v.prediction().opp();
            if (perGame.merge(game, 1, Integer::sum) > 2) {
                continue;
            }
            out.add(v);
            if (out.size() == n) {
                break;
            }
        }
        return out;
    }

    private Optional<NbaContext> nbaContext(OddsRow row, LocalDate date) {
        LeagueData d = models.data(FERMABETS_LEAGUE);
        List<GameRow> history = d.history(row.playerId()).stream().filter(r -> r.date().isBefore(date)).toList();
        if (history.isEmpty()) {
            return Optional.empty();
        }
        String team = history.get(history.size() - 1).team();
        var fixture = repo.fixtureFor(FERMABETS_LEAGUE, date, team);
        if (fixture.isEmpty()) {
            return Optional.empty();
        }
        boolean home = fixture.get().home().equals(team);
        String opp = home ? fixture.get().away() : fixture.get().home();
        double[] usage = new double[history.size()];
        double[] pace = new double[history.size()];
        for (int i = 0; i < history.size(); i++) {
            usage[i] = d.usage(history.get(i));
            pace[i] = d.pace(history.get(i).gameId(), history.get(i).team());
        }
        LocalDate seasonStart = d.currentSeasonStart(date);
        List<GameRow> season = new ArrayList<>();
        List<Double> seasonUsage = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if (!history.get(i).date().isBefore(seasonStart)) {
                season.add(history.get(i));
                seasonUsage.add(usage[i]);
            }
        }
        Projection proj = ProjectionBuilder.build(season,
                seasonUsage.stream().mapToDouble(Double::doubleValue).toArray(),
                d.seasonPace(team, seasonStart, date), d.seasonPace(opp, seasonStart, date));
        List<String> warnings = new ArrayList<>(warningService.forPlayer(FERMABETS_LEAGUE, date, row.playerId(),
                row.playerName(), team, opp, home));
        if (!proj.ready()) {
            warnings.add(0, String.format("Λίγοι φετινοί αγώνες (%d), δεν βγαίνει πρόταση.", proj.games()));
        }
        return Optional.of(new NbaContext(team, opp, home, history, usage, pace, proj, warnings));
    }

    static String canonical(Stat stat) {
        return switch (stat) {
            case PTS -> "pts";
            case REB -> "trb";
            case AST -> "ast";
            case FG3M -> "3pm";
            default -> stat.name().toLowerCase(Locale.ROOT);
        };
    }

    private ValueBet priceNba(OddsRow row, NbaContext c) {
        Double odds = row.over();
        if (odds == null || odds <= 1.0) {
            return null;
        }
        Projection p = c.projection();
        FermabetsModel.Inputs in = new FermabetsModel.Inputs(canonical(row.stat()), row.line(), p.minsBand(),
                p.fgaBand(), p.ftaBand(), p.usgBand(), p.paceBand(), p.fg3aBand(), c.home(), c.opp());
        List<FermabetsModel.Game> games = new ArrayList<>();
        double statSum = 0, minSum = 0;
        for (int i = 0; i < c.history().size(); i++) {
            GameRow r = c.history().get(i);
            games.add(new FermabetsModel.Game(row.stat().of(r), r.minutes(), r.v()[GameRow.FGA], r.v()[GameRow.FTA],
                    r.v()[GameRow.FG3A], c.usage()[i], c.pace()[i], r.home(), r.opp()));
        }
        int from = Math.max(0, c.history().size() - p.games());
        for (int i = from; i < c.history().size(); i++) {
            statSum += row.stat().of(c.history().get(i));
            minSum += c.history().get(i).minutes();
        }
        double mean = minSum > 0 ? statSum / minSum * p.minutes() : Double.NaN;
        FermabetsModel.Result res;
        try {
            res = FermabetsModel.evaluate(games, in);
        } catch (IllegalArgumentException e) {
            return null;
        }
        FermabetsModel.Stake plan = FermabetsModel.stake(res.prob(), odds, settings.bankroll());
        Double under = row.under();
        double pFair = under != null ? (1.0 / odds) / (1.0 / odds + 1.0 / under) : (1.0 / odds) * SINGLE_SIDED_MARGIN;
        FermabetsPick pick = new FermabetsPick(c.team(), c.opp(), c.home(), mean, p, res, plan);
        return new ValueBet(row, "OVER", row.line(), odds, res.prob(), pFair, res.prob(), plan.ev(), plan.stake(),
                plan.ev(), false, pick, explainNba(row, pick), c.warnings());
    }

    static String explainNba(OddsRow row, FermabetsPick pick) {
        Projection p = pick.projection();
        FermabetsModel.Result r = pick.result();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Φετινή σεζόν (%d αγώνες): λεπτά %s, usage %s, FGA %s, FTA %s, 3PA %s",
                p.games(), label(p.minsBand()), label(p.usgBand()), label(p.fgaBand()), label(p.ftaBand()),
                label(p.fg3aBand())));
        sb.append(", pace ").append(label(p.paceBand())).append(". ");
        sb.append(pick.home() ? "Εντός με " : "Εκτός με ").append(pick.opp()).append(" (H2H). ");
        for (FermabetsModel.Factor f : r.factors()) {
            if (f.hitRate() != null) {
                sb.append(String.format(Locale.US, "%s %s: %.0f%% σε %d (βάρος %.0f%%). ", f.name(), f.band(),
                        f.hitRate() * 100, f.n(), f.effectiveWeight() * 100));
            }
        }
        sb.append(String.format(Locale.US, "Φίλτρα %s, similarity %s, μοντέλο %.0f%% για over %.1f.",
                pct(r.pFilters()), pct(r.pSimilarity()), r.prob() * 100, row.line()));
        return sb.toString();
    }

    private static String label(Estimate e) {
        return e == null ? "-" : e.label();
    }

    private static String pct(Double v) {
        return v == null ? "-" : String.format(Locale.US, "%.0f%%", v * 100);
    }

    ValueBet price(OddsRow row, String side, Prediction p, Probs probs) {
        boolean over = side.equals("OVER");
        Double odds = over ? row.over() : row.under();
        if (odds == null || odds < MIN_ODDS || odds > MAX_ODDS) {
            return null;
        }
        double win = over ? probs.over() : probs.under();
        double lose = over ? probs.under() : probs.over();
        double pModel = Math.max(0.01, Math.min(0.99, win / Math.max(1e-9, win + lose) + calibration));
        Double other = over ? row.under() : row.over();
        double pFair = other != null
                ? (1.0 / odds) / (1.0 / odds + 1.0 / other)
                : (1.0 / odds) * SINGLE_SIDED_MARGIN;
        double w = settings.marketWeight();
        double pFinal = (1 - w) * pModel + w * pFair;
        double ev = pFinal * odds - 1.0;
        boolean suspicious = Math.abs(pModel - pFair) > SUSPICIOUS_GAP;
        double score = suspicious ? ev * 0.5 : ev;
        double stake = ev > 0 ? stake(pFinal, odds) : 0.0;
        return new ValueBet(row, side, row.line(), odds, pModel, pFair, pFinal, ev, stake, score, suspicious, p,
                explain(row, side, p, pModel, pFair, pFinal), List.of());
    }

    double stake(double p, double odds) {
        double kelly = Math.max(0.0, (p * odds - 1.0) / (odds - 1.0));
        double raw = settings.bankroll() * kelly * settings.kellyFraction();
        double cap = settings.bankroll() * settings.maxStakePct();
        if (settings.maxStake() > 0) {
            cap = Math.min(cap, settings.maxStake());
        }
        double stake = Math.max(settings.minStake(), Math.min(raw, cap));
        return Math.round(stake);
    }

    static String explain(OddsRow row, String side, Prediction p, double pModel, double pFair, double pFinal) {
        double[] f = p.features();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Projects %.1f %s vs line %.1f (recent form %.1f, ~%.0f min). ",
                p.mean(), row.stat().label(), row.line(), f[0], p.projMinutes()));
        sb.append(String.format("Usage %.0f%% (older form %.0f%%). ", p.usage(), p.formUsage()));
        sb.append(p.home() ? "Home vs " : "Away at ").append(p.opp()).append(". ");
        if (Math.abs(f[6]) >= 0.02) {
            sb.append(String.format("Opponent %s %.0f%% %s than average. ", f[6] > 0 ? "allows" : "limits",
                    Math.abs(f[6]) * 100, f[6] > 0 ? "more" : "less"));
        }
        if (Math.abs(f[7]) >= 0.02) {
            sb.append(String.format("Pace %+.0f%%. ", f[7] * 100));
        }
        if (!p.notes().isEmpty()) {
            sb.append(String.join(", ", p.notes())).append(". ");
        }
        sb.append(String.format("Model %.0f%% vs market %.0f%% -> blended %.0f%% %s.", pModel * 100, pFair * 100,
                pFinal * 100, side.toLowerCase(Locale.ROOT)));
        return sb.toString();
    }
}
