package com.fermabets.web;

import com.fermabets.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Controller
public class OddsController {
    private final OddsService odds;
    private final DataRepository repo;

    public OddsController(OddsService odds, DataRepository repo) {
        this.odds = odds;
        this.repo = repo;
    }

    @GetMapping("/odds")
    public String page(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       Model model) {
        LocalDate day = date == null ? com.fermabets.AppClock.today() : date;
        model.addAttribute("page", "odds");
        model.addAttribute("date", day);
        model.addAttribute("rows", odds.forDate(day));
        model.addAttribute("providers", odds.providerCount());
        model.addAttribute("fixtures", repo.schedule(day));
        model.addAttribute("absences", repo.jdbc().queryForList(
                "SELECT p.name AS name, a.note AS note FROM absence a JOIN player p ON p.id=a.player_id"
                        + " WHERE a.game_date=?", day.toString()));
        return "odds";
    }

    @PostMapping("/odds/import")
    public String importCsv(@RequestParam String text, @RequestParam String bookmaker,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                            RedirectAttributes ra) {
        var result = odds.importCsv(text, bookmaker, date);
        ra.addFlashAttribute("message", "Imported " + result.imported() + " lines.");
        ra.addFlashAttribute("problems", result.problems());
        return "redirect:/odds?date=" + date;
    }

    @PostMapping("/odds/refresh")
    public String refresh(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @RequestParam(required = false) String back, RedirectAttributes ra) {
        var result = odds.refreshFromProviders(date);
        List<String> problems = result.problems();
        ra.addFlashAttribute("message", "Fetched " + result.imported() + " odds lines from bookmakers.");
        ra.addFlashAttribute("problems", problems.size() > 12
                ? new ArrayList<>(problems.subList(0, 12)) {{ add("... and " + (problems.size() - 12) + " more"); }}
                : problems);
        return "dashboard".equals(back) ? "redirect:/?date=" + date : "redirect:/odds?date=" + date;
    }

    @PostMapping("/odds/clear")
    public String clear(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        odds.clear(date);
        return "redirect:/odds?date=" + date;
    }

    @PostMapping("/odds/absence")
    public String absence(@RequestParam String player, @RequestParam String note,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          RedirectAttributes ra) {
        DataRepository.Player match = null;
        for (String league : List.of("NBA", "EL", "EC")) {
            match = PlayerMatcher.match(repo.players(league), player);
            if (match != null) {
                break;
            }
        }
        if (match == null) {
            ra.addFlashAttribute("problems", new ArrayList<>(List.of("Player not found: " + player)));
        } else {
            repo.jdbc().update("INSERT OR REPLACE INTO absence(player_id, game_date, note) VALUES (?,?,?)",
                    match.id(), date.toString(), note);
            ra.addFlashAttribute("message", match.name() + " marked as out.");
        }
        return "redirect:/odds?date=" + date;
    }

    @PostMapping("/odds/absence/clear")
    public String clearAbsences(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        repo.jdbc().update("DELETE FROM absence WHERE game_date=?", date.toString());
        return "redirect:/odds?date=" + date;
    }

    @PostMapping("/odds/crew")
    public String crew(@RequestParam String league, @RequestParam String home, @RequestParam String officials,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       RedirectAttributes ra) {
        repo.jdbc().update("DELETE FROM ref_assignment WHERE league=? AND game_date=? AND home=?", league,
                date.toString(), home);
        int n = 0;
        for (String o : officials.split("[;,\\n]")) {
            if (!o.isBlank()) {
                repo.jdbc().update("INSERT OR IGNORE INTO ref_assignment VALUES (?,?,?,?)", league, date.toString(),
                        home, o.trim());
                n++;
            }
        }
        ra.addFlashAttribute("message", "Saved " + n + " officials for " + home + ".");
        return "redirect:/odds?date=" + date;
    }
}
