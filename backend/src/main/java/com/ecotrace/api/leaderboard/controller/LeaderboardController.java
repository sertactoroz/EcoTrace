package com.ecotrace.api.leaderboard.controller;

import com.ecotrace.api.leaderboard.api.LeaderboardScope;
import com.ecotrace.api.leaderboard.dto.response.LeaderboardResponse;
import com.ecotrace.api.leaderboard.service.LeaderboardService;
import com.ecotrace.api.security.annotation.CurrentUser;
import com.ecotrace.api.security.principal.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/leaderboard")
public class LeaderboardController {

    private final LeaderboardService service;

    public LeaderboardController(LeaderboardService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<LeaderboardResponse> top(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(defaultValue = "GLOBAL") LeaderboardScope scope,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(service.top(scope, limit, user.userId()));
    }
}
