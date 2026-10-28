package com.fermabets.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/** Adds columns that CREATE TABLE IF NOT EXISTS will not attach to an already-created SQLite file. */
@Component
public class SchemaPatcher {
    private final JdbcTemplate jdbc;

    public SchemaPatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void patch() {
        addColumn("team_game", "minutes", "REAL");
    }

    private void addColumn(String table, String column, String decl) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name=?", Integer.class, table, column);
        if (n != null && n == 0) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + decl);
        }
    }
}
