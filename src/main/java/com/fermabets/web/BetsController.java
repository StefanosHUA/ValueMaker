package com.fermabets.web;

import com.fermabets.domain.Stat;
import com.fermabets.service.BetService;
import com.fermabets.service.ClvService;
import com.fermabets.service.ModelService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
public class BetsController {
    private final BetService bets;
    private final ModelService models;

    private final ClvService clv;

    public BetsController(BetService bets, ModelService models, ClvService clv) {
        this.clv = clv;
        this.bets = bets;
        this.models = models;
    }

    @GetMapping("/bets")
    public String list(Model model) {
        model.addAttribute("clv", clv.stats());
        model.addAttribute("page", "bets");
        model.addAttribute("bets", bets.all());
        model.addAttribute("summary", bets.summary());
        return "bets";
    }

    @PostMapping("/bets/place")
    public String place(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                        @RequestParam String league, @RequestParam String bookmaker, @RequestParam String player,
                        @RequestParam String playerId, @RequestParam Stat stat, @RequestParam String side,
                        @RequestParam double line, @RequestParam double odds, @RequestParam double stake,
                        @RequestParam double prob, RedirectAttributes ra) {
        bets.place(date, league, bookmaker, player, playerId, stat, side, line, odds, stake, prob);
        ra.addFlashAttribute("message", "Bet recorded: " + player + " " + side + " " + line);
        return "redirect:/bets";
    }

    @PostMapping("/bets/clv")
    public String runClv(RedirectAttributes ra) {
        clv.run();
        ra.addFlashAttribute("message", "Closing lines and settlement updated.");
        return "redirect:/bets";
    }

    @PostMapping("/bets/settle")
    public String settle(RedirectAttributes ra) {
        ra.addFlashAttribute("message", "Settled " + bets.settlePending() + " bets from ingested box scores.");
        return "redirect:/bets";
    }

    @PostMapping("/bets/{id}/delete")
    public String delete(@PathVariable long id) {
        bets.delete(id);
        return "redirect:/bets";
    }

    @GetMapping("/model")
    public String backtest(@RequestParam(defaultValue = "NBA") String league,
                           @RequestParam(required = false) Stat stat, Model model) {
        model.addAttribute("page", "model");
        model.addAttribute("league", league);
        model.addAttribute("stat", stat);
        model.addAttribute("stats", List.of(Stat.values()));
        if (stat != null) {
            try {
                model.addAttribute("result", models.backtest(league, stat));
            } catch (RuntimeException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        return "model";
    }
}
