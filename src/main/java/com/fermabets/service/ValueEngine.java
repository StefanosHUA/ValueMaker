package com.fermabets.service;

import com.fermabets.service.ModelService.Prediction;
import com.fermabets.service.ModelService.Probs;
import com.fermabets.service.OddsService.OddsRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class ValueEngine {
    static final int MIN_GAMES = 15;
    static final double MIN_PROJ_MINUTES = 12.0;
    static final double MIN_ODDS = 1.40;
    static final double MAX_ODDS = 4.00;
    /** A model/market gap larger than this usually means information the model lacks (injury, role change). */
    static final double SUSPICIOUS_GAP = 0.15;
    static final double SINGLE_SIDED_MARGIN = 0.95;

    public record ValueBet(OddsRow odds, String side, double line, double bookOdds, double pModel, double pFair,
                           double pFinal, double ev, double stake, double score, boolean suspicious,
                           Prediction prediction, String explanation) {
    }

    private final OddsService oddsService;
    private final ModelService models;
    private final SettingsService settings;

    private volatile double calibration = 0.0;

    /** Probability shift learned from settled bets (negative when the model has been overconfident). */
    public void setCalibration(double shift) {
        this.calibration = shift;
    }

    public ValueEngine(OddsService oddsService, ModelService models, SettingsService settings) {
        this.oddsService = oddsService;
        this.models = models;
        this.settings = settings;
    }

    /** All positive-EV candidates for the day, best first, one per player+stat (best price/line shopping). */
    public List<ValueBet> evaluate(LocalDate date) {
        Map<String, Optional<Prediction>> cache = new HashMap<>();
        Map<String, ValueBet> best = new HashMap<>();
        for (OddsRow row : oddsService.forDate(date)) {
            Optional<Prediction> pred;
            try {
                pred = cache.computeIfAbsent(row.playerId() + "|" + row.stat(),
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
                best.merge(row.playerId() + "|" + row.stat(), bet, (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        return best.values().stream().sorted(Comparator.comparingDouble(ValueBet::score).reversed()).toList();
    }

    /** Candidates for one fixture only. */
    public List<ValueBet> evaluateGame(LocalDate date, String league, String home, String away) {
        return evaluate(date).stream().filter(v -> v.odds().league().equals(league)
                && (v.prediction().team().equals(home) || v.prediction().team().equals(away))).toList();
    }

    /** Top picks: positive EV above the threshold, at most two per game. */
    public List<ValueBet> top(List<ValueBet> all, int n) {
        List<ValueBet> out = new ArrayList<>();
        Map<String, Integer> perGame = new HashMap<>();
        for (ValueBet v : all) {
            if (v.ev() < settings.minEv() || v.stake() <= 0) {
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
                explain(row, side, p, pModel, pFair, pFinal));
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
