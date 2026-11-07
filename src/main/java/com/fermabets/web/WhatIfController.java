package com.fermabets.web;

import com.fermabets.model.ProjectionBuilder.Projection;
import com.fermabets.service.ValueEngine;
import com.fermabets.service.ValueEngine.Override;
import com.fermabets.service.ValueEngine.ValueBet;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
public class WhatIfController {
    private final ValueEngine engine;

    public WhatIfController(ValueEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/whatif")
    @ResponseBody
    public Map<String, Object> whatIf(@RequestParam long oddsId,
                                      @RequestParam(required = false) String side,
                                      @RequestParam(required = false) String minutes,
                                      @RequestParam(required = false) String usage,
                                      @RequestParam(required = false) String fga,
                                      @RequestParam(required = false) String fta,
                                      @RequestParam(required = false) String fg3a,
                                      @RequestParam(required = false) String pace) {
        Map<String, Object> body = new LinkedHashMap<>();
        Override override;
        try {
            override = new Override(num(minutes), num(usage), num(fga), num(fta), num(fg3a), num(pace));
        } catch (NumberFormatException e) {
            body.put("ok", false);
            body.put("error", "Άκυρος αριθμός σε κάποιο πεδίο.");
            return body;
        }
        Optional<ValueBet> bet = engine.whatIf(oddsId, override, side);
        if (bet.isEmpty()) {
            body.put("ok", false);
            body.put("error", "Δεν βρέθηκε αυτή η γραμμή ή δεν είναι NBA.");
            return body;
        }
        ValueBet v = bet.get();
        Projection p = v.nbaProjection();
        body.put("ok", true);
        body.put("side", v.side());
        body.put("p", v.pFinal());
        body.put("ev", v.ev());
        body.put("stake", v.stake());
        body.put("mean", v.prediction().mean());
        body.put("explanation", v.explanation());
        if (p != null) {
            body.put("minutes", p.minutes());
            body.put("usage", p.usage());
            body.put("fga", p.fga());
            body.put("fta", p.fta());
            body.put("fg3a", p.fg3a());
            body.put("pace", p.pace());
        }
        List<String> kept = new ArrayList<>();
        if (override.minutes() == null) {
            kept.add("λεπτά");
        }
        if (override.usage() == null) {
            kept.add("usage");
        }
        if (override.fga() == null) {
            kept.add("FGA");
        }
        if (override.fta() == null) {
            kept.add("FTA");
        }
        if (override.fg3a() == null) {
            kept.add("3PA");
        }
        if (override.pace() == null) {
            kept.add("pace");
        }
        body.put("kept", kept);
        body.put("trial", override.any());
        return body;
    }

    static Double num(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return Double.parseDouble(s.trim().replace(',', '.'));
    }
}
