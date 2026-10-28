package com.fermabets.domain;

import java.time.LocalDate;

public record TeamGame(String gameId, String team, String opp, LocalDate date, boolean home,
                       double pts, double poss, double fta, double pf) {
}
