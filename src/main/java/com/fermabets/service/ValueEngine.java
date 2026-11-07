package com.fermabets.service;

import com.fermabets.domain.GameRow;
import com.fermabets.domain.Stat;
import com.fermabets.model.FermabetsModel;
import com.fermabets.model.FermabetsModel.Estimate;
import com.fermabets.model.LeagueData;
import com.fermabets.model.Last5Form;
import com.fermabets.model.BackToBackAdjuster;
import com.fermabets.model.BlowoutAdjuster;
import com.fermabets.model.LineStreak;
import com.fermabets.model.LineupAdjuster;
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
    /** Below this many current-season games, final p is pulled toward 50% (Bühlmann prior). 55/45 stays put. */
    static final int YOUNG_SEASON = 8;
    static final double MIN_PROJ_MINUTES = 12.0;
    static final double MIN_ODDS = 1.40;
    static final double MAX_ODDS = 4.00;
    /** A model/market gap larger than this usually means information the model lacks (injury, role change). */
    static final double SUSPICIOUS_GAP = 0.15;
    static final double SINGLE_SIDED_MARGIN = 0.95;
    static final String FERMABETS_LEAGUE = "NBA";

    public record ValueBet(OddsRow odds, String side, double line, double bookOdds, double pModel, double pFair,
                           double pFinal, double ev, double stake, double score, boolean suspicious,
                           PickInfo prediction, String explanation, List<String> warnings, int fireStreak) {
        public ValueBet(OddsRow odds, String side, double line, double bookOdds, double pModel, double pFair,
                        double pFinal, double ev, double stake, double score, boolean suspicious,
                        PickInfo prediction, String explanation, List<String> warnings) {
            this(odds, side, line, bookOdds, pModel, pFair, pFinal, ev, stake, score, suspicious, prediction,
                    explanation, warnings, 0);
        }

        public boolean fermabets() {
            return prediction instanceof FermabetsPick;
        }

        public Projection nbaProjection() {
            return prediction instanceof FermabetsPick f ? f.projection() : null;
        }

        public boolean onFire() {
            return LineStreak.hot(fireStreak);
        }

        public String fireLabel() {
            return LineStreak.label(fireStreak, side, line);
        }
    }

    /** User numbers for a trial reprice. Null = keep the model's calculated value for that field. */
    public record Override(Double minutes, Double usage, Double fga, Double fta, Double fg3a, Double pace) {
        public static final Override NONE = new Override(null, null, null, null, null, null);

        public boolean any() {
            return minutes != null || usage != null || fga != null || fta != null || fg3a != null || pace != null;
        }
    }

    /** Player context shared by all of his lines on the day. */
    private record NbaContext(String team, String opp, boolean home, List<GameRow> history, double[] usage,
                              double[] pace, Projection projection, List<String> warnings, List<String> lineupNotes,
                              List<LineupAdjuster.Condition> roster, double[] rosterMatch, LocalDate seasonStart,
                              double blowoutHcap, double[] blowoutMatch, double[] b2bMatch) {
    }

    private final OddsService oddsService;
    private final ModelService models;
    private final SettingsService settings;
    private final DataRepository repo;
    private final WarningService warningService;
    private final Map<LocalDate, List<String>> skipped = new ConcurrentHashMap<>();

    private volatile double calibration = 0.0;

    /** Probability shift learned from settled picks (negative when the model has been overconfident). */
    public void setCalibration(double shift) {
        this.calibration = shift;
    }

    public double calibration() {
        return calibration;
    }

    double applyCalibration(double p) {
        return Math.max(0.01, Math.min(0.99, p + calibration));
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

    /** Candidates for the day, best first. Shops the best line per player+stat+side (OVER and UNDER both kept). */
    public List<ValueBet> evaluate(LocalDate date) {
        Map<String, Optional<Prediction>> cache = new HashMap<>();
        Map<String, Optional<NbaContext>> nbaCache = new HashMap<>();
        Map<String, Double> handicaps = repo.homeHandicaps(FERMABETS_LEAGUE);
        Set<String> skippedNow = new TreeSet<>();
        Map<String, ValueBet> best = new HashMap<>();
        for (OddsRow row : oddsService.forDate(date)) {
            String playerStat = row.playerId() + "|" + row.stat();
            if (FERMABETS_LEAGUE.equals(row.league())) {
                Optional<NbaContext> ctx = nbaCache.computeIfAbsent(row.playerId(),
                        k -> nbaContext(row, date, handicaps));
                if (ctx.isEmpty()) {
                    continue;
                }
                if (!ctx.get().projection().ready()) {
                    skippedNow.add(String.format("%s: λίγοι φετινοί αγώνες (%d, χρειάζονται %d), δεν βγαίνει πρόταση.",
                            row.playerName(), ctx.get().projection().games(), ProjectionBuilder.MIN_SEASON_GAMES));
                    continue;
                }
                for (ValueBet bet : priceNbaSides(row, ctx.get())) {
                    best.merge(playerStat + "|" + bet.side(), bet, (a, b) -> a.score() >= b.score() ? a : b);
                }
                continue;
            }
            Optional<Prediction> pred;
            try {
                pred = cache.computeIfAbsent(playerStat,
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
                best.merge(playerStat + "|" + side, bet, (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        skipped.put(date, List.copyOf(skippedNow));
        return best.values().stream().sorted(Comparator.comparingDouble(ValueBet::score).reversed()).toList();
    }

    /** Reprice one NBA line with optional user centres; empty fields stay on the model's projection. */
    public Optional<ValueBet> whatIf(long oddsId, Override override) {
        return whatIf(oddsId, override, "OVER");
    }

    public Optional<ValueBet> whatIf(long oddsId, Override override, String side) {
        Optional<OddsRow> row = oddsService.byId(oddsId);
        if (row.isEmpty() || !FERMABETS_LEAGUE.equals(row.get().league())) {
            return Optional.empty();
        }
        OddsRow r = row.get();
        String s = "UNDER".equalsIgnoreCase(side) ? "UNDER" : "OVER";
        return nbaContext(r, r.date(), repo.homeHandicaps(FERMABETS_LEAGUE))
                .map(c -> priceNba(r, c, override == null ? Override.NONE : override, s))
                .filter(Objects::nonNull);
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

    private Optional<NbaContext> nbaContext(OddsRow row, LocalDate date, Map<String, Double> handicaps) {
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
        double last5W = models.last5Weight(FERMABETS_LEAGUE, date);
        Projection proj = ProjectionBuilder.build(season,
                seasonUsage.stream().mapToDouble(Double::doubleValue).toArray(),
                d.seasonPace(team, seasonStart, date), d.seasonPace(opp, seasonStart, date),
                Last5Form.mix(season.size(), last5W));
        Lineup lineup = tonightLineup(d, team, date);
        List<LineupAdjuster.Condition> roster = LineupAdjuster.conditions(d, row.playerId(), team, date, seasonStart,
                lineup.out(), lineup.back(), lineup.names());
        LineupAdjuster.Adjustment adj = LineupAdjuster.apply(proj, d, row.playerId(), team, date, seasonStart,
                lineup.out(), lineup.back(), lineup.names());
        proj = adj.projection();
        double hcap = tonightHcap(d, date, team, opp, home);
        BlowoutAdjuster.Adjustment blow = BlowoutAdjuster.apply(proj, d, row.playerId(), team, date, seasonStart,
                hcap, null, handicaps);
        proj = blow.projection();
        BackToBackAdjuster.Adjustment b2b = BackToBackAdjuster.apply(proj, d, row.playerId(), team, date, seasonStart);
        proj = b2b.projection();
        double[] rosterMatch = new double[history.size()];
        double[] blowoutMatch = new double[history.size()];
        double[] b2bMatch = new double[history.size()];
        for (int i = 0; i < history.size(); i++) {
            rosterMatch[i] = LineupAdjuster.matchScore(d, team, history.get(i).gameId(), roster);
            blowoutMatch[i] = BlowoutAdjuster.matchScore(d, history.get(i), hcap, null, handicaps);
            b2bMatch[i] = BackToBackAdjuster.matchScore(history, history.get(i), date);
        }
        List<String> warnings = new ArrayList<>(warningService.forPlayer(FERMABETS_LEAGUE, date, row.playerId(),
                row.playerName(), team, opp, home));
        if (!proj.ready()) {
            warnings.add(0, String.format("Λίγοι φετινοί αγώνες (%d), δεν βγαίνει πρόταση.", proj.games()));
        }
        List<String> notes = new ArrayList<>(adj.notes());
        notes.addAll(blow.notes());
        notes.addAll(b2b.notes());
        if (season.size() > Last5Form.GAMES) {
            notes.add(0, String.format(Locale.US, "μίγμα last-5 %.0f%% / σεζόν %.0f%%", last5W * 100,
                    (1 - last5W) * 100));
        }
        return Optional.of(new NbaContext(team, opp, home, history, usage, pace, proj, warnings, notes, roster,
                rosterMatch, seasonStart, hcap, blowoutMatch, b2bMatch));
    }

    /** Warning convention: negative = favourite. Book spread, then game line, then our own rating. */
    double tonightHcap(LeagueData d, LocalDate date, String team, String opp, boolean home) {
        String homeTeam = home ? team : opp;
        String awayTeam = home ? opp : team;
        Optional<DataRepository.Spread> sp = repo.spread(FERMABETS_LEAGUE, date, homeTeam, awayTeam);
        if (sp.isPresent()) {
            return sp.get().forTeam(team);
        }
        Optional<DataRepository.GameMarket> gm = repo.gameMarket(FERMABETS_LEAGUE, date, homeTeam, awayTeam);
        if (gm.isPresent()) {
            return home ? -gm.get().margin() : gm.get().margin();
        }
        return -d.expectedMargin(team, opp, date, home);
    }

    private record Lineup(Set<String> out, Set<String> back, Map<String, String> names) {
    }

    private Lineup tonightLineup(LeagueData d, String team, LocalDate date) {
        Set<String> out = new HashSet<>();
        Set<String> back = new HashSet<>();
        Map<String, String> names = new HashMap<>();
        List<DataRepository.Player> roster = repo.players(FERMABETS_LEAGUE);
        for (DataRepository.Player p : roster) {
            names.put(p.id(), p.name());
        }
        Set<String> uncertain = new HashSet<>();
        if (repo.hasInjuryData(FERMABETS_LEAGUE, date)) {
            for (DataRepository.Injury inj : repo.injuries(FERMABETS_LEAGUE, date, team)) {
                DataRepository.Player match = matchInjury(roster, inj);
                if (match == null) {
                    continue;
                }
                names.put(match.id(), inj.name());
                String st = inj.status() == null ? "" : inj.status();
                if (st.equals("Out") || st.equals("OFS") || st.equals("Doubt") || st.equalsIgnoreCase("Doubtful")) {
                    out.add(match.id());
                    if (st.equals("Doubt") || st.equalsIgnoreCase("Doubtful")) {
                        names.put(match.id(), inj.name() + " (doubtful)");
                    }
                } else if (st.equals("Ques") || st.equals("GTD")) {
                    uncertain.add(match.id());
                } else if (inj.starter() || st.equals("Prob") || st.isBlank()) {
                    back.add(match.id());
                }
            }
        }
        out.addAll(repo.absentees(date));
        back.addAll(d.rotation(team, date).keySet());
        back.removeAll(out);
        back.removeAll(uncertain);
        return new Lineup(out, back, names);
    }

    static DataRepository.Player matchInjury(List<DataRepository.Player> players, DataRepository.Injury inj) {
        for (DataRepository.Player p : players) {
            if (p.key().equals(inj.key())) {
                return p;
            }
        }
        return PlayerMatcher.match(players, inj.name());
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

    private List<ValueBet> priceNbaSides(OddsRow row, NbaContext c) {
        List<ValueBet> out = new ArrayList<>();
        ValueBet over = priceNba(row, c, Override.NONE, "OVER");
        ValueBet under = priceNba(row, c, Override.NONE, "UNDER");
        if (over != null) {
            out.add(over);
        }
        if (under != null) {
            out.add(under);
        }
        return out;
    }

    private ValueBet priceNba(OddsRow row, NbaContext c, Override override, String side) {
        boolean wantOver = !"UNDER".equalsIgnoreCase(side);
        Double odds = wantOver ? row.over() : row.under();
        if (odds == null || odds < MIN_ODDS || odds > MAX_ODDS) {
            return null;
        }
        Projection base = c.projection();
        Override o = override == null ? Override.NONE : override;
        Projection proj = ProjectionBuilder.override(base, o.minutes(), o.usage(), o.fga(), o.fta(), o.fg3a(),
                o.pace());
        FermabetsModel.Inputs in = new FermabetsModel.Inputs(canonical(row.stat()), row.line(), proj.minsBand(),
                proj.fgaBand(), proj.ftaBand(), proj.usgBand(), proj.paceBand(), proj.fg3aBand(), c.home(), c.opp());
        List<FermabetsModel.Game> games = new ArrayList<>();
        double statSum = 0, minSum = 0;
        for (int i = 0; i < c.history().size(); i++) {
            GameRow r = c.history().get(i);
            games.add(new FermabetsModel.Game(row.stat().of(r), r.minutes(), r.v()[GameRow.FGA], r.v()[GameRow.FTA],
                    r.v()[GameRow.FG3A], c.usage()[i], c.pace()[i], r.home(), r.opp()));
        }
        int from = Math.max(0, c.history().size() - proj.games());
        for (int i = from; i < c.history().size(); i++) {
            statSum += row.stat().of(c.history().get(i));
            minSum += c.history().get(i).minutes();
        }
        double mean = minSum > 0 ? statSum / minSum * proj.minutes() : Double.NaN;
        double[] weights = null;
        int nMatch = 0, matchHits = 0, nBlow = 0, blowHits = 0, nB2b = 0, b2bHits = 0;
        boolean hasRoster = c.roster() != null && !c.roster().isEmpty();
        boolean hasBlow = c.blowoutMatch() != null && WarningService.isBlowout(c.blowoutHcap());
        boolean hasB2b = c.b2bMatch() != null && java.util.Arrays.stream(c.b2bMatch()).anyMatch(x -> x >= 1.0 - 1e-9);
        if (hasRoster || hasBlow || hasB2b) {
            weights = new double[c.history().size()];
            for (int i = 0; i < weights.length; i++) {
                boolean thisSeason = !c.history().get(i).date().isBefore(c.seasonStart());
                double roster = hasRoster && thisSeason ? c.rosterMatch()[i] : 0;
                double blow = hasBlow ? c.blowoutMatch()[i] : 0;
                double b2bW = hasB2b ? c.b2bMatch()[i] : 0;
                weights[i] = 1.0 + LineupAdjuster.ROSTER_BOOST * roster + BlowoutAdjuster.MATCH_BOOST * blow
                        + BackToBackAdjuster.MATCH_BOOST * b2bW;
                if (roster >= 1.0 - 1e-9) {
                    nMatch++;
                    if (row.stat().of(c.history().get(i)) > row.line()) {
                        matchHits++;
                    }
                }
                if (blow >= 1.0 - 1e-9) {
                    nBlow++;
                    if (row.stat().of(c.history().get(i)) > row.line()) {
                        blowHits++;
                    }
                }
                if (b2bW >= 1.0 - 1e-9) {
                    nB2b++;
                    if (row.stat().of(c.history().get(i)) > row.line()) {
                        b2bHits++;
                    }
                }
            }
        }
        FermabetsModel.Result res;
        try {
            res = FermabetsModel.evaluate(games, in, weights);
        } catch (IllegalArgumentException e) {
            return null;
        }
        double prob = res.prob();
        List<String> notes = new ArrayList<>(c.lineupNotes());
        double share = LineupAdjuster.blendShare(nMatch);
        if (share > 0) {
            double pRoster = matchHits / (double) nMatch;
            prob = (1.0 - share) * prob + share * pRoster;
            String who = c.roster().stream().map(LineupAdjuster.Condition::label).reduce((a, b) -> a + ", " + b)
                    .orElse("ρόστερ");
            notes.add(String.format(Locale.US,
                    "Ίδια σύνθεση (%s): over %.0f%% σε %d αγώνες, βάρος %.0f%% στο p.",
                    who, pRoster * 100, nMatch, share * 100));
        }
        double blowShare = LineupAdjuster.blendShare(nBlow);
        if (blowShare > 0) {
            double pBlow = blowHits / (double) nBlow;
            prob = (1.0 - blowShare) * prob + blowShare * pBlow;
            notes.add(String.format(Locale.US,
                    "Ίδια συνθήκη blowout: over %.0f%% σε %d αγώνες, βάρος %.0f%% στο p.",
                    pBlow * 100, nBlow, blowShare * 100));
        }
        double b2bShare = LineupAdjuster.blendShare(nB2b);
        if (b2bShare > 0) {
            double pB2b = b2bHits / (double) nB2b;
            prob = (1.0 - b2bShare) * prob + b2bShare * pB2b;
            notes.add(String.format(Locale.US,
                    "Ίδια συνθήκη back-to-back: over %.0f%% σε %d αγώνες, βάρος %.0f%% στο p.",
                    pB2b * 100, nB2b, b2bShare * 100));
        }
        double unshrunk = prob;
        prob = shrinkYoungSeason(prob, proj.games());
        if (Math.abs(prob - unshrunk) >= 0.005) {
            notes.add(String.format(Locale.US,
                    "Λίγα φετινά δείγματα (%d): p μαζεύτηκε από %.0f%% προς 50%% → %.0f%%.",
                    proj.games(), unshrunk * 100, prob * 100));
        }
        double raw = prob;
        prob = applyCalibration(prob);
        if (Math.abs(prob - raw) >= 0.005) {
            notes.add(String.format(Locale.US, "Εκπαίδευση από αποτελέσματα: p %+.1f pp.", (prob - raw) * 100));
        }
        if (o.any()) {
            notes.add(describeOverride(base, proj, o));
        }
        String sideName = wantOver ? "OVER" : "UNDER";
        double pOver = prob;
        double pSide = pForSide(pOver, sideName);
        FermabetsModel.Stake plan = FermabetsModel.stake(pSide, odds, settings.bankroll());
        Double other = wantOver ? row.under() : row.over();
        double pFair = other != null ? (1.0 / odds) / (1.0 / odds + 1.0 / other) : (1.0 / odds) * SINGLE_SIDED_MARGIN;
        FermabetsPick pick = new FermabetsPick(c.team(), c.opp(), c.home(), mean, proj, res, plan);
        int fire = LineStreak.of(c.history(), row.stat(), row.line(), wantOver);
        return new ValueBet(row, sideName, row.line(), odds, pSide, pFair, pSide, plan.ev(), plan.stake(),
                plan.ev(), false, pick, explainNba(row, pick, notes, pSide, sideName), c.warnings(), fire);
    }

    /** Complementary probability: UNDER is 1 − P(over) after shrink/calibration. */
    static double pForSide(double pOver, String side) {
        return "UNDER".equalsIgnoreCase(side) ? 1.0 - pOver : pOver;
    }

    static String explainNba(OddsRow row, FermabetsPick pick, List<String> lineupNotes) {
        return explainNba(row, pick, lineupNotes, pick.result().prob(), "OVER");
    }

    static String explainNba(OddsRow row, FermabetsPick pick, List<String> lineupNotes, double prob, String side) {
        Projection p = pick.projection();
        FermabetsModel.Result r = pick.result();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Φετινή σεζόν (%d αγώνες%s): λεπτά %s, usage %s, FGA %s, FTA %s, 3PA %s",
                p.games(), p.games() > ProjectionBuilder.LAST5 ? ", μίγμα + φόρμα 5 αγώνων" : "",
                label(p.minsBand()), label(p.usgBand()), label(p.fgaBand()), label(p.ftaBand()),
                label(p.fg3aBand())));
        sb.append(", pace ").append(label(p.paceBand())).append(". ");
        sb.append(pick.home() ? "Εντός με " : "Εκτός με ").append(pick.opp()).append(" (H2H). ");
        for (FermabetsModel.Factor f : r.factors()) {
            if (f.hitRate() != null) {
                sb.append(String.format(Locale.US, "%s %s: %.0f%% σε %d (βάρος %.0f%%). ", f.name(), f.band(),
                        f.hitRate() * 100, f.n(), f.effectiveWeight() * 100));
            }
        }
        if (lineupNotes != null && !lineupNotes.isEmpty()) {
            sb.append("Σύνθεση: ").append(String.join(" ", lineupNotes)).append(' ');
        }
        sb.append(String.format(Locale.US, "Φίλτρα %s, similarity %s, μοντέλο %.0f%% για %s %.1f.",
                pct(r.pFilters()), pct(r.pSimilarity()), prob * 100, side.toLowerCase(Locale.ROOT), row.line()));
        return sb.toString();
    }

    /**
     * The 55/45 hit rates can sit at 100% after 3 matching games because Z only shrinks factor weights, not the
     * rate toward a prior. Early in the season, pull the blended p toward 50%.
     */
    static double shrinkYoungSeason(double p, int games) {
        if (games >= YOUNG_SEASON) {
            return p;
        }
        double z = FermabetsModel.credibility(Math.max(0, games), FermabetsModel.BUHLMANN_K);
        return 0.5 + z * (p - 0.5);
    }

    static String describeOverride(Projection before, Projection after, Override o) {
        List<String> bits = new ArrayList<>();
        if (o.minutes() != null) {
            bits.add(String.format(Locale.US, "λεπτά %.1f (μοντέλο %.1f)", after.minutes(), before.minutes()));
        }
        if (o.usage() != null) {
            bits.add(String.format(Locale.US, "usage %.1f (μοντέλο %.1f)", after.usage(), before.usage()));
        }
        if (o.fga() != null) {
            bits.add(String.format(Locale.US, "FGA %.1f (μοντέλο %.1f)", after.fga(), before.fga()));
        }
        if (o.fta() != null) {
            bits.add(String.format(Locale.US, "FTA %.1f (μοντέλο %.1f)", after.fta(), before.fta()));
        }
        if (o.fg3a() != null) {
            bits.add(String.format(Locale.US, "3PA %.1f (μοντέλο %.1f)", after.fg3a(), before.fg3a()));
        }
        if (o.pace() != null) {
            bits.add(String.format(Locale.US, "pace %.1f (μοντέλο %.1f)", after.pace(), before.pace()));
        }
        return "Δοκιμή: " + String.join(", ", bits) + ". Κενά πεδία έμειναν ως τα υπολόγισε το μοντέλο.";
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
        double pModel = applyCalibration(win / Math.max(1e-9, win + lose));
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
        int fire = LineStreak.of(p.history(), row.stat(), row.line(), over);
        return new ValueBet(row, side, row.line(), odds, pModel, pFair, pFinal, ev, stake, score, suspicious, p,
                explain(row, side, p, pModel, pFair, pFinal), List.of(), fire);
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
