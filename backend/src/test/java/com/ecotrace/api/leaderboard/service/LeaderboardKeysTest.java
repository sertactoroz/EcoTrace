package com.ecotrace.api.leaderboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecotrace.api.leaderboard.api.LeaderboardScope;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LeaderboardKeysTest {

    @Test
    void global_key_is_static() {
        assertThat(LeaderboardKeys.resolve(LeaderboardScope.GLOBAL, LocalDate.of(2026, 5, 9)))
                .isEqualTo("lb:global");
    }

    @Test
    void weekly_key_uses_iso_week_and_year() {
        // 2026-01-01 is a Thursday → ISO week 1 of 2026
        assertThat(LeaderboardKeys.resolve(LeaderboardScope.WEEKLY, LocalDate.of(2026, 1, 1)))
                .isEqualTo("lb:weekly:2026-W01");
        // 2025-12-29 is a Monday in ISO week 1 of 2026
        assertThat(LeaderboardKeys.resolve(LeaderboardScope.WEEKLY, LocalDate.of(2025, 12, 29)))
                .isEqualTo("lb:weekly:2026-W01");
        assertThat(LeaderboardKeys.resolve(LeaderboardScope.WEEKLY, LocalDate.of(2026, 5, 9)))
                .isEqualTo("lb:weekly:2026-W19");
    }

    @Test
    void monthly_key_uses_yyyy_mm() {
        assertThat(LeaderboardKeys.resolve(LeaderboardScope.MONTHLY, LocalDate.of(2026, 5, 9)))
                .isEqualTo("lb:monthly:2026-05");
        assertThat(LeaderboardKeys.resolve(LeaderboardScope.MONTHLY, LocalDate.of(2026, 12, 31)))
                .isEqualTo("lb:monthly:2026-12");
    }

    @Test
    void ttls_are_zero_for_global_and_finite_for_rolling() {
        assertThat(LeaderboardKeys.ttlFor(LeaderboardScope.GLOBAL)).isEqualTo(Duration.ZERO);
        assertThat(LeaderboardKeys.ttlFor(LeaderboardScope.WEEKLY)).isEqualTo(Duration.ofDays(14));
        assertThat(LeaderboardKeys.ttlFor(LeaderboardScope.MONTHLY)).isEqualTo(Duration.ofDays(62));
    }
}
