package com.ecotrace.api.leaderboard.service;

import com.ecotrace.api.leaderboard.api.LeaderboardScope;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.Locale;

final class LeaderboardKeys {

    private static final WeekFields ISO = WeekFields.ISO;
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

    private LeaderboardKeys() {}

    static String resolve(LeaderboardScope scope, LocalDate today) {
        return switch (scope) {
            case GLOBAL -> "lb:global";
            case WEEKLY -> "lb:weekly:" + weekStamp(today);
            case MONTHLY -> "lb:monthly:" + today.format(MONTH_FMT);
        };
    }

    static Duration ttlFor(LeaderboardScope scope) {
        return switch (scope) {
            case GLOBAL -> Duration.ZERO;
            case WEEKLY -> Duration.ofDays(14);
            case MONTHLY -> Duration.ofDays(62);
        };
    }

    static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private static String weekStamp(LocalDate d) {
        int week = d.get(ISO.weekOfWeekBasedYear());
        int year = d.get(ISO.weekBasedYear());
        return String.format(Locale.ROOT, "%04d-W%02d", year, week);
    }
}
