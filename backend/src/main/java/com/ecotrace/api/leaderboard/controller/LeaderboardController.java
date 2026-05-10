package com.ecotrace.api.leaderboard.controller;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.leaderboard.api.LeaderboardScope;
import com.ecotrace.api.leaderboard.dto.response.LeaderboardEntryResponse;
import com.ecotrace.api.leaderboard.dto.response.LeaderboardResponse;
import com.ecotrace.api.leaderboard.service.LeaderboardService;
import com.ecotrace.api.security.annotation.CurrentUser;
import com.ecotrace.api.security.principal.AuthenticatedUser;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/leaderboards")
public class LeaderboardController {

    private final LeaderboardService service;

    public LeaderboardController(LeaderboardService service) {
        this.service = service;
    }

    @GetMapping("/{scope}")
    public ResponseEntity<LeaderboardResponse> top(
            @PathVariable String scope,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(service.top(parseScope(scope), limit));
    }

    @GetMapping("/{scope}/me")
    public ResponseEntity<LeaderboardEntryResponse> me(
            @CurrentUser AuthenticatedUser user,
            @PathVariable String scope) {
        LeaderboardEntryResponse entry = service.me(parseScope(scope), user.userId());
        if (entry == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "No rank in scope " + scope);
        }
        return ResponseEntity.ok(entry);
    }

    private static LeaderboardScope parseScope(String raw) {
        try {
            return LeaderboardScope.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Unknown leaderboard scope: " + raw);
        }
    }
}
