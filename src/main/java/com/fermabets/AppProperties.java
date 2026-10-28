package com.fermabets;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fermabets")
public record AppProperties(
        String dbPath,
        String ingestDir,
        String python,
        double bankroll,
        double kellyFraction,
        double minStake,
        double maxStake,
        double maxStakePct,
        double minEv,
        double marketWeight) {
}
