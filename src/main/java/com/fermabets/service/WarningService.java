package com.fermabets.service;

import com.fermabets.service.DataRepository.Injury;
import com.fermabets.service.DataRepository.Spread;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Injury and blowout warnings. Nothing here changes the model probability or the stake.
 */
@Service
public class WarningService {
    public static final double BLOWOUT_SPREAD = 8.5;
    static final Set<String> TEAMMATE = Set.of("Out", "Doubt", "Ques", "GTD");
    static final Map<String, String> STATUS_GR = Map.of(
            "Out", "εκτός",
            "OFS", "εκτός για τη σεζόν",
            "Doubt", "αμφίβολος",
            "Ques", "αβέβαιος",
            "Prob", "πιθανός να παίξει",
            "GTD", "απόφαση την ώρα του αγώνα");

    private final DataRepository repo;

    public WarningService(DataRepository repo) {
        this.repo = repo;
    }

    public List<String> forPlayer(String league, LocalDate date, String playerId, String playerName, String team,
                                  String opp, boolean home) {
        String homeTeam = home ? team : opp;
        String awayTeam = home ? opp : team;
        boolean have = repo.hasInjuryData(league, date);
        List<Injury> mine = repo.injuries(league, date, team);
        Optional<Spread> spread = repo.spread(league, date, homeTeam, awayTeam);
        String key = repo.playerKey(playerId).orElseGet(() -> PlayerMatcher.key(playerName));
        return build(playerName, key, team, opp, home, mine, spread, have);
    }

    /** Game-level lines for the match page (both sides + the handicap). */
    public List<String> forGame(String league, LocalDate date, String home, String away) {
        List<String> out = new ArrayList<>();
        boolean have = repo.hasInjuryData(league, date);
        if (!have) {
            out.add("RotoWire δεν άνοιξε, δεν ελέγχθηκαν τραυματισμοί.");
        } else {
            out.addAll(teamInjuries(home, repo.injuries(league, date, home)));
            out.addAll(teamInjuries(away, repo.injuries(league, date, away)));
        }
        Optional<Spread> spread = repo.spread(league, date, home, away);
        out.add(blowoutLine(home, spread.map(s -> s.forTeam(home)).orElse(null)));
        return out;
    }

    static List<String> build(String playerName, String playerKey, String team, String opp, boolean home,
                              List<Injury> mine, Optional<Spread> spread, boolean haveInjuries) {
        List<String> out = new ArrayList<>();
        if (!haveInjuries) {
            out.add("RotoWire δεν άνοιξε, δεν ελέγχθηκαν τραυματισμοί και handicap.");
        } else {
            String where = home ? "εντός" : "εκτός";
            out.add("RotoWire: " + team + " " + where + " με " + opp + ".");
            Injury me = find(mine, playerName, playerKey);
            if (me != null && me.status() != null && !me.status().isBlank()) {
                String label = STATUS_GR.getOrDefault(me.status(), me.status());
                out.add("Ο ίδιος ο " + playerName + " είναι " + label + " (" + me.status() + ").");
            } else if (me != null) {
                out.add(me.starter()
                        ? playerName + ": στην αναμενόμενη πεντάδα."
                        : playerName + ": εκτός πεντάδας.");
            }
            List<Injury> hurt = new ArrayList<>();
            List<String> ofs = new ArrayList<>();
            for (Injury p : mine) {
                if (me != null && samePlayer(playerName, playerKey, p)) {
                    continue;
                }
                String st = p.status() == null ? "" : p.status();
                if (TEAMMATE.contains(st)) {
                    hurt.add(p);
                } else if (st.equals("OFS")) {
                    ofs.add(p.name());
                }
            }
            if (!hurt.isEmpty()) {
                List<String> listed = new ArrayList<>();
                for (Injury p : hurt) {
                    listed.add(p.name() + " " + STATUS_GR.getOrDefault(p.status(), p.status()));
                }
                out.add("Συμπαίκτες εκτός ή αβέβαιοι: " + String.join(", ", listed)
                        + ". Μπορεί να αλλάξουν λεπτά και usage, έλεγξε τα εύρη.");
            }
            if (!ofs.isEmpty()) {
                out.add("Εκτός σεζόν (ήδη μέσα στο πρόσφατο ιστορικό): " + String.join(", ", ofs) + ".");
            }
        }
        Double hcap = spread.map(s -> s.forTeam(team)).orElse(null);
        out.add(blowoutLine(team, hcap));
        return out;
    }

    public static boolean isBlowout(double handicap) {
        return Math.abs(handicap) > BLOWOUT_SPREAD;
    }

    static String blowoutLine(String team, Double hcap) {
        if (hcap == null) {
            return "Δεν βρέθηκε handicap.";
        }
        if (isBlowout(hcap)) {
            String role = hcap < 0 ? "φαβορί" : "αουτσάιντερ";
            String effect = hcap < 0
                    ? "Οι βασικοί μπορεί να κάτσουν νωρίς στο τέλος"
                    : "Μπορεί να υπάρξουν λεπτά garbage time ή να κάτσουν οι βασικοί";
            return String.format(Locale.US, "ΚΙΝΔΥΝΟΣ BLOWOUT: handicap %+.1f, %s %s. %s.", hcap, team, role, effect);
        }
        return String.format(Locale.US, "Handicap %+.1f, κάτω από το όριο blowout %.1f.", hcap, BLOWOUT_SPREAD);
    }

    private static List<String> teamInjuries(String team, List<Injury> list) {
        List<String> hurt = new ArrayList<>();
        for (Injury p : list) {
            String st = p.status() == null ? "" : p.status();
            if (TEAMMATE.contains(st) || st.equals("OFS")) {
                hurt.add(p.name() + " " + STATUS_GR.getOrDefault(st, st));
            }
        }
        if (hurt.isEmpty()) {
            return List.of();
        }
        return List.of(team + ": " + String.join(", ", hurt) + ".");
    }

    private static Injury find(List<Injury> mine, String playerName, String playerKey) {
        for (Injury p : mine) {
            if (samePlayer(playerName, playerKey, p)) {
                return p;
            }
        }
        return null;
    }

    static boolean samePlayer(String playerName, String playerKey, Injury inj) {
        String k = playerKey == null || playerKey.isBlank() ? PlayerMatcher.key(playerName) : playerKey;
        String ik = inj.key() == null || inj.key().isBlank() ? PlayerMatcher.key(inj.name()) : inj.key();
        if (k.equals(ik)) {
            return true;
        }
        if (k.length() > 3 && ik.contains(k)) {
            return true;
        }
        if (ik.length() > 3 && k.contains(ik)) {
            return true;
        }
        return PlayerMatcher.key(playerName).equals(PlayerMatcher.key(inj.name()));
    }
}
