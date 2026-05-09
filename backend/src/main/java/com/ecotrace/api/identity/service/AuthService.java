package com.ecotrace.api.identity.service;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.config.properties.AuthProperties;
import com.ecotrace.api.identity.dto.request.GoogleAuthRequest;
import com.ecotrace.api.identity.dto.request.RefreshRequest;
import com.ecotrace.api.identity.dto.response.AuthTokensResponse;
import com.ecotrace.api.identity.dto.response.UserResponse;
import com.ecotrace.api.identity.entity.AuthProvider;
import com.ecotrace.api.identity.entity.RoleName;
import com.ecotrace.api.identity.entity.User;
import com.ecotrace.api.identity.entity.UserAuthProvider;
import com.ecotrace.api.identity.entity.UserStatus;
import com.ecotrace.api.identity.repository.UserAuthProviderRepository;
import com.ecotrace.api.identity.repository.UserRepository;
import com.ecotrace.api.security.jwt.JwtBlocklistService;
import com.ecotrace.api.security.jwt.JwtService;
import com.ecotrace.api.security.oauth2.GoogleTokenVerifier;
import com.ecotrace.api.security.principal.AuthenticatedUser;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final GoogleTokenVerifier googleVerifier;
    private final UserRepository users;
    private final UserAuthProviderRepository providers;
    private final JwtService jwt;
    private final RefreshTokenStore refreshStore;
    private final JwtBlocklistService blocklist;
    private final AuthProperties authProps;
    private final UserRoleService userRoles;

    public AuthService(GoogleTokenVerifier googleVerifier,
                       UserRepository users,
                       UserAuthProviderRepository providers,
                       JwtService jwt,
                       RefreshTokenStore refreshStore,
                       JwtBlocklistService blocklist,
                       AuthProperties authProps,
                       UserRoleService userRoles) {
        this.googleVerifier = googleVerifier;
        this.users = users;
        this.providers = providers;
        this.jwt = jwt;
        this.refreshStore = refreshStore;
        this.blocklist = blocklist;
        this.authProps = authProps;
        this.userRoles = userRoles;
    }

    @Transactional
    public AuthTokensResponse loginWithGoogle(GoogleAuthRequest req) {
        GoogleTokenVerifier.GoogleIdentity identity;
        try {
            identity = googleVerifier.verify(req.idToken());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, e.getMessage());
        }

        User user = providers.findByProviderAndProviderUserId(AuthProvider.GOOGLE, identity.sub())
                .map(p -> users.findById(p.getUserId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL, "Provider points to missing user")))
                .orElseGet(() -> users.findByEmail(identity.email())
                        .map(existing -> linkGoogleProvider(existing, identity))
                        .orElseGet(() -> createUser(identity)));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_SUSPENDED, "User is not active");
        }

        user.setLastActiveAt(OffsetDateTime.now(ZoneOffset.UTC));

        return issueTokens(user, req.deviceId());
    }

    @Transactional
    public AuthTokensResponse refresh(RefreshRequest req) {
        JwtService.ParsedToken parsed;
        try {
            parsed = jwt.verify(req.refreshToken());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, e.getMessage());
        }
        var record = refreshStore.find(parsed.jti())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN, "Refresh token not recognized"));
        if (!record.userId().equals(parsed.user().userId())) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "Refresh token mismatch");
        }
        refreshStore.delete(parsed.jti());

        User user = users.findById(parsed.user().userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_SUSPENDED, "User is not active");
        }
        return issueTokens(user, record.deviceId());
    }

    public void logout(String accessJti, Instant accessExpiresAt, String refreshJti) {
        if (accessJti != null && accessExpiresAt != null) {
            blocklist.revoke(accessJti, accessExpiresAt);
        }
        if (refreshJti != null) {
            refreshStore.delete(refreshJti);
        }
    }

    private User createUser(GoogleTokenVerifier.GoogleIdentity identity) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(identity.email());
        u.setDisplayName(identity.name() != null ? identity.name() : defaultName(identity.email()));
        u.setAvatarUrl(identity.pictureUrl());
        u.setStatus(UserStatus.ACTIVE);
        u.setLevel(1);
        u.setLocale("en");
        u = users.save(u);

        UserAuthProvider link = new UserAuthProvider();
        link.setId(UUID.randomUUID());
        link.setUserId(u.getId());
        link.setProvider(AuthProvider.GOOGLE);
        link.setProviderUserId(identity.sub());
        link.setEmailAtProvider(identity.email());
        providers.save(link);
        return u;
    }

    private User linkGoogleProvider(User existing, GoogleTokenVerifier.GoogleIdentity identity) {
        UserAuthProvider link = new UserAuthProvider();
        link.setId(UUID.randomUUID());
        link.setUserId(existing.getId());
        link.setProvider(AuthProvider.GOOGLE);
        link.setProviderUserId(identity.sub());
        link.setEmailAtProvider(identity.email());
        providers.save(link);
        return existing;
    }

    private static String defaultName(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private AuthTokensResponse issueTokens(User user, String deviceId) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail(), rolesFor(user));
        JwtService.IssuedToken access = jwt.issueAccessToken(principal);
        JwtService.IssuedToken refresh = jwt.issueRefreshToken(principal);
        refreshStore.store(refresh.jti(),
                new RefreshTokenStore.RefreshRecord(user.getId(), deviceId, refresh.expiresAt()),
                java.time.Duration.between(Instant.now(), refresh.expiresAt()));
        return new AuthTokensResponse(
                access.token(),
                OffsetDateTime.ofInstant(access.expiresAt(), ZoneOffset.UTC),
                refresh.token(),
                OffsetDateTime.ofInstant(refresh.expiresAt(), ZoneOffset.UTC),
                toResponse(user));
    }

    Set<String> rolesFor(User user) {
        Set<RoleName> dbRoles = userRoles.rolesFor(user.getId());

        boolean allowlisted = authProps.moderatorEmails().stream()
                .anyMatch(e -> e.equalsIgnoreCase(user.getEmail()));
        if (allowlisted && !dbRoles.contains(RoleName.MODERATOR)) {
            // Bootstrap: first login from a configured email — promote to DB role.
            if (userRoles.grantIfMissing(user.getId(), RoleName.MODERATOR, null)) {
                dbRoles.add(RoleName.MODERATOR);
            }
        }

        Set<String> result = new HashSet<>();
        result.add("USER");
        for (RoleName r : dbRoles) result.add(r.name());
        return result;
    }

    private UserResponse toResponse(User u) {
        return new UserResponse(
                u.getId(),
                u.getEmail(),
                u.getDisplayName(),
                u.getAvatarUrl(),
                u.getTotalPoints(),
                u.getLevel(),
                u.getStatus().name());
    }
}
