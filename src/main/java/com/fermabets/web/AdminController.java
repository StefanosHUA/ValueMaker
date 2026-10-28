package com.fermabets.web;

import com.fermabets.service.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class AdminController {
    private final IngestService ingest;
    private final SettingsService settings;
    private final ModelService models;
    private final BetService bets;

    public AdminController(IngestService ingest, SettingsService settings, ModelService models, BetService bets) {
        this.ingest = ingest;
        this.settings = settings;
        this.models = models;
        this.bets = bets;
    }

    @GetMapping
    public String admin(Model model) {
        model.addAttribute("page", "admin");
        model.addAttribute("summary", ingest.dataSummary());
        model.addAttribute("officials", ingest.officialsCount());
        model.addAttribute("runs", ingest.recentRuns());
        model.addAttribute("settings", settings);
        model.addAttribute("status", ingest.status());
        return "admin";
    }

    @PostMapping("/ingest/{source}")
    public String start(@PathVariable String source, RedirectAttributes ra) {
        IngestService.Source s = IngestService.Source.valueOf(source.toUpperCase());
        ra.addFlashAttribute("message", ingest.start(s) ? "Started " + s : "An ingestion is already running.");
        return "redirect:/admin";
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public IngestService.Status status() {
        return ingest.status();
    }

    @PostMapping("/settings")
    public String saveSettings(@RequestParam double bankroll, @RequestParam double kellyFraction,
                               @RequestParam double minStake, @RequestParam double maxStake,
                               @RequestParam double minEv, @RequestParam double marketWeight,
                               RedirectAttributes ra) {
        settings.set("bankroll", bankroll);
        settings.set("kellyFraction", kellyFraction);
        settings.set("minStake", minStake);
        settings.set("maxStake", maxStake);
        settings.set("minEv", minEv);
        settings.set("marketWeight", marketWeight);
        ra.addFlashAttribute("message", "Settings saved.");
        return "redirect:/admin";
    }

    @PostMapping("/retrain")
    public String retrain(RedirectAttributes ra) {
        models.invalidate();
        ra.addFlashAttribute("message", "Model cache cleared; models retrain on next use.");
        return "redirect:/admin";
    }

    @PostMapping("/settle")
    public String settle(RedirectAttributes ra) {
        ra.addFlashAttribute("message", "Settled " + bets.settlePending() + " bets.");
        return "redirect:/admin";
    }
}
