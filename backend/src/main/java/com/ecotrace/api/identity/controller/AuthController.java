package com.ecotrace.api.identity.controller;

import com.ecotrace.api.identity.dto.request.GoogleAuthRequest;
import com.ecotrace.api.identity.dto.request.RefreshRequest;
import com.ecotrace.api.identity.dto.response.AuthTokensResponse;
import com.ecotrace.api.identity.service.AuthService;
import com.ecotrace.api.security.jwt.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @PostMapping("/google")
    public ResponseEntity<AuthTokensResponse> google(@Valid @RequestBody GoogleAuthRequest req) {
        return ResponseEntity.ok(authService.loginWithGoogle(req));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthTokensResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ResponseEntity.ok(authService.refresh(req));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, @RequestBody(required = false) RefreshRequest req) {
        String header = request.getHeader("Authorization");
        String accessJti = null;
        java.time.Instant accessExp = null;
        if (header != null && header.startsWith("Bearer ")) {
            try {
                JwtService.ParsedToken parsed = jwtService.verify(header.substring(7));
                accessJti = parsed.jti();
                accessExp = parsed.expiresAt();
            } catch (IllegalArgumentException ignored) {
            }
        }
        String refreshJti = null;
        if (req != null && req.refreshToken() != null) {
            try {
                refreshJti = jwtService.verify(req.refreshToken()).jti();
            } catch (IllegalArgumentException ignored) {
            }
        }
        authService.logout(accessJti, accessExp, refreshJti);
        return ResponseEntity.noContent().build();
    }
}
