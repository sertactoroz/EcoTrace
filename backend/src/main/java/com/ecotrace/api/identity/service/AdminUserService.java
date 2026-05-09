package com.ecotrace.api.identity.service;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.identity.dto.response.UserRolesResponse;
import com.ecotrace.api.identity.entity.RoleName;
import com.ecotrace.api.identity.entity.User;
import com.ecotrace.api.identity.repository.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {

    private final UserRepository users;
    private final UserRoleService userRoles;

    public AdminUserService(UserRepository users, UserRoleService userRoles) {
        this.users = users;
        this.userRoles = userRoles;
    }

    @Transactional(readOnly = true)
    public UserRolesResponse findByEmail(String email) {
        User u = users.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
        return toResponse(u);
    }

    @Transactional(readOnly = true)
    public UserRolesResponse findById(UUID userId) {
        User u = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
        return toResponse(u);
    }

    @Transactional
    public UserRolesResponse grant(UUID actorUserId, UUID targetUserId, RoleName role) {
        if (role == RoleName.ADMIN) {
            // ADMIN role can only be bootstrapped via the configured allowlist, never granted via API.
            throw new BusinessException(ErrorCode.FORBIDDEN, "ADMIN role cannot be granted via API");
        }
        users.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
        userRoles.grantIfMissing(targetUserId, role, actorUserId);
        return findById(targetUserId);
    }

    @Transactional
    public UserRolesResponse revoke(UUID actorUserId, UUID targetUserId, RoleName role) {
        if (role == RoleName.ADMIN && actorUserId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Cannot revoke your own ADMIN role");
        }
        users.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
        userRoles.revoke(targetUserId, role);
        return findById(targetUserId);
    }

    private UserRolesResponse toResponse(User u) {
        return new UserRolesResponse(u.getId(), u.getEmail(), u.getDisplayName(), userRoles.rolesFor(u.getId()));
    }
}
