package com.fermabets.service;

import com.fermabets.domain.Stat;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OddsAndMatcherTest {

    @Test
    void parsesCsvRowWithDefaults() {
        var line = OddsService.parseRow(new String[]{"", "", "Luka Doncic", "pts", "28,5", "1.85", "1.95"},
                "Manual", LocalDate.of(2026, 10, 28));
        assertEquals("Manual", line.bookmaker());
        assertEquals(Stat.PTS, line.stat());
        assertEquals(28.5, line.line());
        assertEquals(1.95, line.under());
    }

    @Test
    void rejectsBadOddsAndStat() {
        assertThrows(IllegalArgumentException.class, () -> OddsService.parseRow(
                new String[]{"", "", "X Y", "pts", "20.5", "0.9"}, "M", LocalDate.now()));
        assertThrows(IllegalArgumentException.class, () -> OddsService.parseRow(
                new String[]{"", "", "X Y", "dunks", "20.5", "1.9"}, "M", LocalDate.now()));
    }

    @Test
    void matchesNamesAcrossFormats() {
        var luka = new DataRepository.Player("NBA:1", "Luka Dončić", PlayerMatcher.key("Luka Dončić"), "LAL",
                LocalDate.now());
        var other = new DataRepository.Player("NBA:2", "Lamar Stevens", PlayerMatcher.key("Lamar Stevens"), "CLE",
                LocalDate.now());
        var players = List.of(luka, other);
        assertSame(luka, PlayerMatcher.match(players, "Luka Doncic"));
        assertSame(luka, PlayerMatcher.match(players, "L. Doncic"));
        assertSame(luka, PlayerMatcher.match(players, "Doncic Luka"));
        assertNull(PlayerMatcher.match(players, "Nobody Here"));
    }
}
