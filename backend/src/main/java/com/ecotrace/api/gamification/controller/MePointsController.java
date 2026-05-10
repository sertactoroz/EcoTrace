package com.ecotrace.api.gamification.controller;

import com.ecotrace.api.gamification.dto.response.LevelResponse;
import com.ecotrace.api.gamification.dto.response.PointsHistoryResponse;
import com.ecotrace.api.gamification.service.LevelService;
import com.ecotrace.api.gamification.service.PointsHistoryService;
import com.ecotrace.api.identity.api.UserPointsFacade;
import com.ecotrace.api.security.annotation.CurrentUser;
import com.ecotrace.api.security.principal.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me")
public class MePointsController {

    private final LevelService levelService;
    private final UserPointsFacade userPoints;
    private final PointsHistoryService history;

    public MePointsController(LevelService levelService,
                              UserPointsFacade userPoints,
                              PointsHistoryService history) {
        this.levelService = levelService;
        this.userPoints = userPoints;
        this.history = history;
    }

    @GetMapping("/level")
    public ResponseEntity<LevelResponse> level(@CurrentUser AuthenticatedUser user) {
        long total = userPoints.getTotalPoints(user.userId());
        LevelService.LevelSnapshot s = levelService.describe(total);
        return ResponseEntity.ok(new LevelResponse(
                s.level(), s.name(), s.currentMinPoints(),
                s.nextLevelMinPoints(), s.pointsToNextLevel(), s.totalPoints()));
    }

    @GetMapping("/points/transactions")
    public ResponseEntity<PointsHistoryResponse> transactions(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok(history.history(user.userId(), limit, cursor));
    }
}
