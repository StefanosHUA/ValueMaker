package com.fermabets.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** A bettable stat; combined stats (PRA...) sum base components. */
public enum Stat {
    PTS("points", GameRow.PTS),
    REB("rebounds", GameRow.REB),
    AST("assists", GameRow.AST),
    FG3M("threes", GameRow.FG3M),
    STL("steals", GameRow.STL),
    BLK("blocks", GameRow.BLK),
    TOV("turnovers", GameRow.TOV),
    PRA("pts+reb+ast", GameRow.PTS, GameRow.REB, GameRow.AST),
    PR("pts+reb", GameRow.PTS, GameRow.REB),
    PA("pts+ast", GameRow.PTS, GameRow.AST),
    RA("reb+ast", GameRow.REB, GameRow.AST);

    private final String label;
    private final int[] parts;

    Stat(String label, int... parts) {
        this.label = label;
        this.parts = parts;
    }

    public String label() {
        return label;
    }

    public double of(double[] values) {
        double sum = 0;
        for (int p : parts) {
            sum += values[p];
        }
        return sum;
    }

    public double of(GameRow row) {
        return of(row.v());
    }

    public static Optional<Stat> parse(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+]", "");
        return switch (t) {
            case "pts", "points", "point", "p" -> Optional.of(PTS);
            case "reb", "rebs", "trb", "rebounds", "rebound", "r" -> Optional.of(REB);
            case "ast", "assists", "assist", "a" -> Optional.of(AST);
            case "3pm", "fg3m", "threes", "three", "3pt", "3pts", "triples", "3pointers", "threepointers" ->
                    Optional.of(FG3M);
            case "stl", "steals", "steal" -> Optional.of(STL);
            case "blk", "blocks", "block" -> Optional.of(BLK);
            case "tov", "to", "turnovers", "turnover" -> Optional.of(TOV);
            case "pra", "pts+reb+ast" -> Optional.of(PRA);
            case "pr", "pts+reb" -> Optional.of(PR);
            case "pa", "pts+ast" -> Optional.of(PA);
            case "ra", "reb+ast" -> Optional.of(RA);
            default -> Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(t)).findFirst();
        };
    }
}
