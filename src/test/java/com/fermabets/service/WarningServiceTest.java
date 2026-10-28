package com.fermabets.service;

import com.fermabets.service.DataRepository.Injury;
import com.fermabets.service.DataRepository.Spread;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WarningServiceTest {

    @Test
    void blowoutThresholdIsStrictlyAboveEightPointFive() {
        assertFalse(WarningService.isBlowout(8.5));
        assertFalse(WarningService.isBlowout(-8.5));
        assertTrue(WarningService.isBlowout(8.6));
        assertTrue(WarningService.isBlowout(-9.5));
    }

    @Test
    void favouriteBlowoutUsesGreekWarning() {
        String line = WarningService.blowoutLine("CLE", -9.5);
        assertTrue(line.contains("ΚΙΝΔΥΝΟΣ BLOWOUT"));
        assertTrue(line.contains("φαβορί"));
        assertFalse(WarningService.blowoutLine("CLE", -7.5).contains("BLOWOUT"));
    }

    @Test
    void teammateInjuriesAndPlayerStatusAreListed() {
        List<Injury> mine = List.of(
                new Injury("CHA", "LaMelo Ball", "lameloball", "", true),
                new Injury("CHA", "Grant Williams", "grantwilliams", "Out", false),
                new Injury("CHA", "Mark Williams", "markwilliams", "Ques", false));
        Optional<Spread> spread = Optional.of(new Spread("CLE", "CHA", -9.5, "stoiximan"));
        List<String> lines = WarningService.build("LaMelo Ball", "lameloball", "CHA", "CLE", false, mine, spread, true);
        assertTrue(lines.stream().anyMatch(s -> s.contains("αναμενόμενη πεντάδα")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("Grant Williams") && s.contains("Mark Williams")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("Μπορεί να αλλάξουν λεπτά και usage")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("ΚΙΝΔΥΝΟΣ BLOWOUT") && s.contains("αουτσάιντερ")));
        assertTrue(lines.stream().noneMatch(s -> s.contains("Ο ίδιος ο LaMelo")));
    }

    @Test
    void missingRotowireIsAWarningNotACrash() {
        List<String> lines = WarningService.build("X", "x", "CHA", "CLE", false, List.of(), Optional.empty(), false);
        assertTrue(lines.get(0).contains("RotoWire δεν άνοιξε"));
        assertTrue(lines.stream().anyMatch(s -> s.contains("Δεν βρέθηκε handicap")));
    }
}
