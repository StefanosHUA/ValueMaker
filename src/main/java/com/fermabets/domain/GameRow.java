package com.fermabets.domain;

import java.time.LocalDate;

/** One player appearance. v = {pts, reb, ast, fg3m, fg3a, fga, fta, stl, blk, tov}. */
public record GameRow(String gameId, String playerId, String league, LocalDate date, String team, String opp,
                      boolean home, double minutes, double[] v) {
    public static final int PTS = 0, REB = 1, AST = 2, FG3M = 3, FG3A = 4, FGA = 5, FTA = 6, STL = 7, BLK = 8, TOV = 9;
    public static final int N_STATS = 10;
}
