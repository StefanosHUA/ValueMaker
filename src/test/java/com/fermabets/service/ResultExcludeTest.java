package com.fermabets.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResultExcludeTest {

    @Test
    void notExcludedSqlJoinsFixtureAndPlayer() {
        String sql = DataRepository.notExcludedSql("pick_log");
        assertTrue(sql.contains("exclude_game"));
        assertTrue(sql.contains("pick_log.player_id"));
        assertTrue(sql.contains("pick_log.game_date"));
        assertTrue(sql.contains("NOT EXISTS"));
    }
}
