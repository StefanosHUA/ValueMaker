package com.fermabets.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Matches bookmaker player names ("L. Doncic", "Doncic Luka", Greek-transliterated) to database players. */
public final class PlayerMatcher {
    private PlayerMatcher() {
    }

    public static String key(String name) {
        String n = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static DataRepository.Player match(List<DataRepository.Player> players, String input) {
        String k = key(input);
        if (k.isEmpty()) {
            return null;
        }
        for (var p : players) {
            if (p.key().equals(k)) {
                return p;
            }
        }
        String[] tokens = (input == null ? "" : input).trim().split("[\\s.,]+");
        if (tokens.length < 2) {
            return null;
        }
        // Try "First Last" and "Last First" with the first token reduced to an initial.
        List<DataRepository.Player> found = new ArrayList<>();
        for (int flip = 0; flip < 2; flip++) {
            String first = key(tokens[flip == 0 ? 0 : tokens.length - 1]);
            String last = key(String.join("", java.util.Arrays.copyOfRange(tokens, flip == 0 ? 1 : 0,
                    flip == 0 ? tokens.length : tokens.length - 1)));
            if (first.isEmpty() || last.isEmpty()) {
                continue;
            }
            for (var p : players) {
                if (p.key().endsWith(last) && p.key().startsWith(first.substring(0, 1))
                        && p.key().length() >= last.length() + 1 && !found.contains(p)) {
                    found.add(p);
                }
            }
            if (!found.isEmpty()) {
                break;
            }
        }
        if (found.size() == 1) {
            return found.get(0);
        }
        return null;
    }
}
