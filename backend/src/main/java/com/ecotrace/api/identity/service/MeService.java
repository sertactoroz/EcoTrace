package com.ecotrace.api.identity.service;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.identity.dto.response.MeResponse;
import com.ecotrace.api.identity.entity.RoleName;
import com.ecotrace.api.identity.entity.User;
import com.ecotrace.api.identity.repository.UserRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeService {

    private final UserRepository users;
    private final UserRoleService userRoles;

    public MeService(UserRepository users, UserRoleService userRoles) {
        this.users = users;
        this.userRoles = userRoles;
    }

    @Transactional(readOnly = true)
    public MeResponse load(UUID userId) {
        User u = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
        Set<String> roles = new HashSet<>();
        roles.add("USER");
        for (RoleName r : userRoles.rolesFor(userId)) roles.add(r.name());
        return new MeResponse(
                u.getId(),
                u.getEmail(),
                u.getDisplayName(),
                u.getAvatarUrl(),
                u.getBio(),
                u.getTotalPoints(),
                u.getLevel(),
                u.getStatus().name(),
                u.getLocale(),
                u.getLastActiveAt(),
                roles);
    }
}
