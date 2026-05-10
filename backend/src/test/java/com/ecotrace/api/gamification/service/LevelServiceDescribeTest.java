package com.ecotrace.api.gamification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ecotrace.api.gamification.entity.Level;
import com.ecotrace.api.gamification.repository.LevelRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LevelServiceDescribeTest {

    private final LevelRepository repo = Mockito.mock(LevelRepository.class);
    private LevelService svc;

    @BeforeEach
    void setUp() {
        when(repo.findAll()).thenReturn(List.of(
                level(1, "Beginner", 0),
                level(2, "Eco Friend", 100),
                level(3, "Eco Warrior", 300),
                level(4, "Green Master", 700)));
        svc = new LevelService(repo);
        svc.load();
    }

    @Test
    void below_first_threshold_reports_level_1() {
        var s = svc.describe(0);
        assertThat(s.level()).isEqualTo(1);
        assertThat(s.name()).isEqualTo("Beginner");
        assertThat(s.currentMinPoints()).isEqualTo(0);
        assertThat(s.nextLevelMinPoints()).isEqualTo(100);
        assertThat(s.pointsToNextLevel()).isEqualTo(100L);
    }

    @Test
    void mid_range_reports_correct_level_and_next_threshold() {
        var s = svc.describe(150);
        assertThat(s.level()).isEqualTo(2);
        assertThat(s.currentMinPoints()).isEqualTo(100);
        assertThat(s.nextLevelMinPoints()).isEqualTo(300);
        assertThat(s.pointsToNextLevel()).isEqualTo(150L);
    }

    @Test
    void exactly_at_threshold_promotes_to_that_level() {
        var s = svc.describe(300);
        assertThat(s.level()).isEqualTo(3);
        assertThat(s.currentMinPoints()).isEqualTo(300);
        assertThat(s.pointsToNextLevel()).isEqualTo(400L);
    }

    @Test
    void at_top_level_has_null_next_threshold() {
        var s = svc.describe(9999);
        assertThat(s.level()).isEqualTo(4);
        assertThat(s.nextLevelMinPoints()).isNull();
        assertThat(s.pointsToNextLevel()).isNull();
    }

    @Test
    void empty_snapshot_falls_back_to_level_1() {
        when(repo.findAll()).thenReturn(List.of());
        LevelService empty = new LevelService(repo);
        empty.load();

        var s = empty.describe(50);
        assertThat(s.level()).isEqualTo(1);
        assertThat(s.totalPoints()).isEqualTo(50L);
        assertThat(s.nextLevelMinPoints()).isNull();
    }

    private static Level level(int id, String name, int min) {
        Level l = new Level();
        l.setLevel(id);
        l.setName(name);
        l.setMinPoints(min);
        return l;
    }
}
