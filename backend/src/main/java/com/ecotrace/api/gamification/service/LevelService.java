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
}
