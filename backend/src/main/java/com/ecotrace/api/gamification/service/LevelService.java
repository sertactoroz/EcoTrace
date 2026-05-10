package com.ecotrace.api.gamification.service;

import com.ecotrace.api.gamification.entity.Level;
import com.ecotrace.api.gamification.repository.LevelRepository;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class LevelService {

    private final LevelRepository levels;
    private final AtomicReference<List<Level>> snapshot = new AtomicReference<>(List.of());

    public LevelService(LevelRepository levels) {
        this.levels = levels;
    }

    @PostConstruct
    void load() {
        List<Level> all = levels.findAll().stream()
                .sorted(Comparator.comparingInt(Level::getMinPoints))
                .toList();
        snapshot.set(all);
    }

    public int compute(long totalPoints) {
        int current = 1;
        for (Level l : snapshot.get()) {
            if (totalPoints >= l.getMinPoints()) {
                current = l.getLevel();
            } else {
                break;
            }
        }
        return current;
    }

    public LevelSnapshot describe(long totalPoints) {
        List<Level> all = snapshot.get();
        Level current = null;
        Level next = null;
        for (Level l : all) {
            if (totalPoints >= l.getMinPoints()) {
                current = l;
            } else {
                next = l;
                break;
            }
        }
        if (current == null) {
            // No levels seeded — degenerate case; report level 1 with no thresholds.
            return new LevelSnapshot(1, "Beginner", 0, null, null, totalPoints);
        }
        Integer nextMin = next == null ? null : next.getMinPoints();
        Long pointsToNext = next == null ? null : Math.max(0L, next.getMinPoints() - totalPoints);
        return new LevelSnapshot(
                current.getLevel(),
                current.getName(),
                current.getMinPoints(),
                nextMin,
                pointsToNext,
                totalPoints);
    }

    public record LevelSnapshot(
            int level,
            String name,
            int currentMinPoints,
            Integer nextLevelMinPoints,
            Long pointsToNextLevel,
            long totalPoints) {}
}
