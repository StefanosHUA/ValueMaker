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
public class DashboardController {
    private final ValueEngine engine;
    private final DataRepository repo;
    private final SettingsService settings;
    private final OddsService odds;
    private final BetService bets;

    public DashboardController(ValueEngine engine, DataRepository repo, SettingsService settings, OddsService odds,
                               BetService bets) {
        this.engine = engine;
        this.repo = repo;
        this.settings = settings;
        this.odds = odds;
        this.bets = bets;
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                            Model model) {
        LocalDate today = AppClock.today();
        LocalDate day = date == null ? today : date;
        model.addAttribute("date", day);
        model.addAttribute("today", today);
        model.addAttribute("yesterday", today.minusDays(1));
        model.addAttribute("tomorrow", today.plusDays(1));
        model.addAttribute("page", "dashboard");
        model.addAttribute("fixtures", repo.schedule(day));
        model.addAttribute("settings", settings);
        model.addAttribute("summary", bets.summary());
        model.addAttribute("providers", odds.providerCount());
        List<OddsService.OddsRow> rows = odds.forDate(day);
        model.addAttribute("oddsCount", rows.size());
        model.addAttribute("lastFetch", odds.lastFetch(day).orElse(null));
        try {
            List<ValueEngine.ValueBet> all = engine.evaluate(day);
            List<ValueEngine.ValueBet> top = engine.top(all, 3);
            model.addAttribute("top", top);
            model.addAttribute("all", all);
            model.addAttribute("edges", all.stream().filter(v -> v.ev() >= 0.15).limit(12).toList());
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
            model.addAttribute("skipped", engine.skipped(day));
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "dashboard";
    }
}
