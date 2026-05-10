package com.ecotrace.api.gamification.dto.response;

public record LevelResponse(
        int level,
        String name,
        int currentMinPoints,
        Integer nextLevelMinPoints,
        Long pointsToNextLevel,
        long totalPoints) {}
