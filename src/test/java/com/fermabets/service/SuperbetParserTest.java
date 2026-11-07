package com.fermabets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermabets.domain.Stat;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SuperbetParserTest {
    private static final String EVENT = """
            {"event_id":15450661,"fixture":{"event_name":"Atlanta Hawks·Cleveland Cavaliers",
              "utc_date":"2026-10-30T00:00:00Z"},
             "markets":[
               {"name":"To Win the Match (incl. overtime)","odds":[
                 {"price":1.92,"status":1,"metadata":{"code":"1"}},
                 {"price":1.88,"status":1,"metadata":{"code":"2"}}]},
               {"name":"Points Handicap (incl. overtime)","odds":[
                 {"price":1.90,"status":1,"metadata":{"code":"1","market_line_uuid":"h1",
                   "specifiers":{"hcp":"-3.5"}}},
                 {"price":1.90,"status":1,"metadata":{"code":"2","market_line_uuid":"h1",
                   "specifiers":{"hcp":"3.5"}}}]},
               {"name":"Total Points (incl. overtime)","odds":[
                 {"price":1.90,"status":1,"metadata":{"name":"Over 220.5","market_line_uuid":"t1",
                   "specifiers":{"total":"220.5"}}},
                 {"price":1.90,"status":1,"metadata":{"name":"Under 220.5","market_line_uuid":"t1",
                   "specifiers":{"total":"220.5"}}}]},
               {"name":"Player - Total Points (incl. overtime)","odds":[
                 {"price":1.83,"status":1,"metadata":{"name":"Alexander-Walker, Nickeil - Over 17.5",
                   "specifiers":{"player":"Alexander-Walker, Nickeil","total":"17.5"}}},
                 {"price":1.90,"status":1,"metadata":{"name":"Alexander-Walker, Nickeil - Under 17.5",
                   "specifiers":{"player":"Alexander-Walker, Nickeil","total":"17.5"}}},
                 {"price":1.01,"status":2,"metadata":{"name":"Allen, Jarrett - Over 12.5",
                   "specifiers":{"player":"Allen, Jarrett","total":"12.5"}}}]},
               {"name":"Player - Total Assists (incl. overtime)","odds":[
                 {"price":1.59,"status":1,"metadata":{"name":"Alexander-Walker, Nickeil - Over 2.5",
                   "specifiers":{"player":"Alexander-Walker, Nickeil","total":"2.5"}}},
                 {"price":2.25,"status":1,"metadata":{"name":"Alexander-Walker, Nickeil - Under 2.5",
                   "specifiers":{"player":"Alexander-Walker, Nickeil","total":"2.5"}}}]},
               {"name":"Player Points (incl. overtime)","odds":[
                 {"price":1.01,"status":2,"metadata":{"name":"Alexander-Walker, Nickeil 5+",
                   "specifiers":{"player":"Alexander-Walker, Nickeil","milestone":"5"}}},
                 {"price":2.22,"status":1,"metadata":{"name":"Alexander-Walker, Nickeil 20+",
                   "specifiers":{"player":"Alexander-Walker, Nickeil","milestone":"20"}}},
                 {"price":1.83,"status":1,"metadata":{"name":"Alexander-Walker, Nickeil 18+",
                   "specifiers":{"player":"Alexander-Walker, Nickeil","milestone":"18"}}}]}
             ]}
            """;

    @Test
    void parsesPlayerTotalsAndFlipsLastFirstNames() throws Exception {
        List<OddsService.OddsLine> lines = SuperbetProvider.parseEvent(new ObjectMapper().readTree(EVENT),
                LocalDate.of(2026, 10, 29), "15450661");
        assertEquals(3, lines.size());
        var pts = lines.stream().filter(l -> l.stat() == Stat.PTS && l.line() == 17.5).findFirst().orElseThrow();
        assertEquals("Nickeil Alexander-Walker", pts.playerName());
        assertEquals(1.83, pts.over());
        assertEquals(1.90, pts.under());
        assertEquals("Superbet", pts.bookmaker());
        var ast = lines.stream().filter(l -> l.stat() == Stat.AST).findFirst().orElseThrow();
        assertEquals(2.5, ast.line());
        assertEquals(1.59, ast.over());
    }

    @Test
    void keepsOverUnderAndAddsMilestoneAlts() throws Exception {
        List<OddsService.OddsLine> lines = SuperbetProvider.parseEvent(new ObjectMapper().readTree(EVENT),
                LocalDate.of(2026, 10, 29), "15450661");
        var alt = lines.stream().filter(l -> l.stat() == Stat.PTS && l.line() == 19.5).findFirst().orElseThrow();
        assertEquals(2.22, alt.over());
        assertNull(alt.under());
        var main = lines.stream().filter(l -> l.stat() == Stat.PTS && l.line() == 17.5).findFirst().orElseThrow();
        assertEquals(1.90, main.under());
        assertTrue(lines.stream().noneMatch(l -> l.line() == 4.5));
    }

    @Test
    void mainHandicapAndTotalGiveExpectedMargin() throws Exception {
        var g = SuperbetProvider.parseGame(new ObjectMapper().readTree(EVENT), LocalDate.of(2026, 10, 29), "15450661")
                .orElseThrow();
        assertEquals(3.5, g.margin(), 0.01);
        assertEquals(220.5, g.total(), 0.01);
        assertEquals("NBA", g.league());
        assertTrue(g.homeGroup().endsWith("homeRoster"));
        assertNotNull(g.homeWin());
        assertEquals(0.5, g.homeWin(), 0.02);
    }

    @Test
    void displayNameFlipsCommaForm() {
        assertEquals("Donovan Mitchell", SuperbetProvider.displayName("Mitchell, Donovan"));
        assertEquals("Luka Doncic", SuperbetProvider.displayName("Luka Doncic"));
    }
}
