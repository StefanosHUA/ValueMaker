package com.fermabets.service;

import com.fermabets.AppProperties;
import org.springframework.stereotype.Service;

/** Runtime-adjustable settings: UI overrides stored in the DB win over application.properties defaults. */
@Service
public class SettingsService {
    private final DataRepository repo;
    private final AppProperties props;

    public SettingsService(DataRepository repo, AppProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public double bankroll() {
        return get("bankroll", props.bankroll());
    }

    public double kellyFraction() {
        return get("kellyFraction", props.kellyFraction());
    }

    public double minStake() {
        return get("minStake", props.minStake());
    }

    public double maxStake() {
        return get("maxStake", props.maxStake());
    }

    public double minEv() {
        return get("minEv", props.minEv());
    }

    public double marketWeight() {
        return get("marketWeight", props.marketWeight());
    }

    public double maxStakePct() {
        return props.maxStakePct();
    }

    public void set(String key, double value) {
        repo.saveSetting(key, Double.toString(value));
    }

    private double get(String key, double fallback) {
        return repo.setting(key).map(Double::parseDouble).orElse(fallback);
    }
}
