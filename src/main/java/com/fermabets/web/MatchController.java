package com.fermabets.web;

import com.fermabets.AppClock;
import com.fermabets.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
public class MatchController {
    private final DataRepository repo;
    private final ModelService models;
    private final ValueEngine engine;
    private final OddsService odds;
    private final SettingsService settings;
    private final WarningService warnings;

    public MatchController(DataRepository repo, ModelService models, ValueEngine engine, OddsService odds,
                           SettingsService settings, WarningService warnings) {
        this.repo = repo;
        this.models = models;
        this.engine = engine;
        this.odds = odds;
        this.settings = settings;
        this.warnings = warnings;
    }

    @GetMapping("/matches")
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       Model model) {
        LocalDate today = AppClock.today();
        LocalDate day = date == null ? today : date;
        model.addAttribute("page", "matches");
        model.addAttribute("date", day);
        model.addAttribute("today", today);
        model.addAttribute("yesterday", today.minusDays(1));
        model.addAttribute("tomorrow", today.plusDays(1));
        model.addAttribute("fixtures", repo.schedule(day));
        model.addAttribute("oddsCount", odds.forDate(day).size());
        return "matches";
    }

    @GetMapping("/match")
    public String match(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                        @RequestParam String league, @RequestParam String home, @RequestParam String away,
                        @RequestParam(defaultValue = "false") boolean calc,
                        @RequestParam(defaultValue = "false") boolean fetch, Model model) {
        model.addAttribute("page", "matches");
        model.addAttribute("date", date);
        model.addAttribute("league", league);
        model.addAttribute("home", home);
        model.addAttribute("away", away);
        model.addAttribute("calc", calc);
        model.addAttribute("settings", settings);
        try {
            if (fetch) {
                var r = odds.refreshFromProviders(date);
                model.addAttribute("message", "Fetched " + r.imported() + " odds lines.");
            }
            var gm = repo.gameMarket(league, date, home, away);
            model.addAttribute("gm", gm.orElse(null));
            double own = models.data(league).expectedMargin(home, away, date, true);
            model.addAttribute("ownMargin", own);
            double mg = gm.isPresent() ? gm.get().margin() : own;
            model.addAttribute("gameNote", Math.abs(mg) >= 9.0
                    ? "Likely lopsided: " + (mg > 0 ? home : away) + " are heavy favourites. Their starters risk reduced"
                    + " minutes and the underdog's bench gets garbage time - overs on favourite stars are shaded down."
                    : Math.abs(mg) <= 3.0
                    ? "Close matchup: starters should play heavy minutes and usage concentrates on the stars."
                    : "Moderate favourite: little blowout effect expected.");
            model.addAttribute("gameWarnings", warnings.forGame(league, date, home, away));
            model.addAttribute("homePlayers", models.teamView(league, home, date));
            model.addAttribute("awayPlayers", models.teamView(league, away, date));
            if (calc || fetch) {
                List<ValueEngine.ValueBet> all = engine.evaluateGame(date, league, home, away);
                model.addAttribute("top", engine.top(all, 6));
                model.addAttribute("all", all);
                Map<Long, String> moves = new HashMap<>();
                for (ValueEngine.ValueBet v : all) {
                    odds.opening(v.odds()).ifPresent(o -> {
                        Double was = v.side().equals("OVER") ? o.over() : o.under();
                        if (was != null && Math.abs(was - v.bookOdds()) >= 0.01) {
                            moves.put(v.odds().id(), String.format(Locale.US, "%.2f -> %.2f", was, v.bookOdds()));
                        }
                    });
                }
                model.addAttribute("moves", moves);
            }
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "match";
    }
}
