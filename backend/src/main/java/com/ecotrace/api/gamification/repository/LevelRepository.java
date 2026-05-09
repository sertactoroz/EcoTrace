package com.ecotrace.api.gamification.repository;

import com.ecotrace.api.gamification.entity.Level;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LevelRepository extends JpaRepository<Level, Integer> {
}
