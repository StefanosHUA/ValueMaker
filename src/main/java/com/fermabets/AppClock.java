package com.fermabets;

import java.time.LocalDate;
import java.time.ZoneId;

/** NBA game dates are US (Eastern) dates; at night in Greece "today's" NBA slate is still yesterday's date. */
public final class AppClock {
    public static final ZoneId NBA_ZONE = ZoneId.of("America/New_York");
    public static final ZoneId LOCAL_ZONE = ZoneId.of("Europe/Athens");

    private AppClock() {
    }

    public static LocalDate today() {
        return LocalDate.now(NBA_ZONE);
    }
}
