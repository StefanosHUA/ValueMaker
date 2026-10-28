package com.fermabets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoiximanGameLineTest {
    @Test
    void mainHandicapAndTotalGiveExpectedMarginAndTotal() throws Exception {
        String json = "{\"data\":{\"markets\":{"
                + "\"1\":{\"typeId\":156,\"selectionIdList\":[\"a\",\"b\"]},"
                + "\"2\":{\"typeId\":156,\"selectionIdList\":[\"c\",\"d\"]},"
                + "\"3\":{\"typeId\":157,\"selectionIdList\":[\"e\",\"f\"]}},"
                + "\"selections\":{"
                + "\"a\":{\"name\":\"Home Team -3.5\",\"handicap\":-3.5,\"price\":1.90},"
                + "\"b\":{\"name\":\"Away Team +3.5\",\"handicap\":3.5,\"price\":1.90},"
                + "\"c\":{\"name\":\"Home Team -9.5\",\"handicap\":-9.5,\"price\":2.80},"
                + "\"d\":{\"name\":\"Away Team +9.5\",\"handicap\":9.5,\"price\":1.40},"
                + "\"e\":{\"name\":\"Over\",\"handicap\":220.5,\"price\":1.90},"
                + "\"f\":{\"name\":\"Under\",\"handicap\":220.5,\"price\":1.90}}}}";
        var g = StoiximanProvider.parseGame(new ObjectMapper().readTree(json), LocalDate.of(2026, 10, 27), "1", "NBA",
                "Home  Team - Away Team", 12, 18).orElseThrow();
        assertEquals(3.5, g.margin(), 0.01);
        assertEquals(220.5, g.total(), 0.01);
        assertTrue(g.homeGroup().endsWith("homeRoster"));
    }
}