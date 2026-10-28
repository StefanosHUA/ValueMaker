package com.fermabets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermabets.domain.Stat;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StoiximanParserTest {
    private static final String JSON = """
            {"data":{
              "roster":{"homeRoster":{"players":{"1":{"id":1,"name":"Mitchell Robinson"}}},
                        "awayRoster":{"players":{"2":{"id":2,"name":"Paul George"}}}},
              "markets":{
                "10":{"typeId":1970,"playerId":1,"name":"x [Mitchell Robinson]","selectionIdList":[100,101]},
                "11":{"typeId":1856,"playerId":2,"name":"Paul George","selectionIdList":[102,103]},
                "12":{"typeId":1856,"playerId":1,"name":"Mitchell Robinson","selectionIdList":[104]}},
              "selections":{
                "100":{"name":"Over 5.5","price":1.72,"handicap":5.5},
                "101":{"name":"Under 5.5","price":1.95,"handicap":5.5},
                "102":{"shortName":"16+","price":1.47},
                "103":{"shortName":"17+","price":1.6},
                "104":{"shortName":"6+","price":1.5}}}}
            """;

    @Test
    void parsesOverUnderAndMilestones() throws Exception {
        List<OddsService.OddsLine> lines = StoiximanProvider.parseEvent(new ObjectMapper().readTree(JSON),
                LocalDate.of(2026, 10, 27));
        assertEquals(3, lines.size());
        var ou = lines.get(0);
        assertEquals("Mitchell Robinson", ou.playerName());
        assertEquals(Stat.PTS, ou.stat());
        assertEquals(5.5, ou.line());
        assertEquals(1.72, ou.over());
        assertEquals(1.95, ou.under());
        var ms = lines.stream().filter(l -> l.playerName().equals("Paul George") && l.line() == 15.5).findFirst()
                .orElseThrow();
        assertEquals(1.47, ms.over());
        assertNull(ms.under());
    }
}
