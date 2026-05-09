package com.ecotrace.api.identity.service;

import com.ecotrace.api.identity.entity.RoleName;
import com.ecotrace.api.identity.entity.UserRole;
import com.ecotrace.api.identity.repository.UserRoleRepository;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRoleService {

    private final UserRoleRepository roles;

    public UserRoleService(UserRoleRepository roles) {
        this.roles = roles;
    }

    @Transactional(readOnly = true)
    public Set<RoleName> rolesFor(UUID userId) {
        Set<RoleName> result = EnumSet.noneOf(RoleName.class);
        for (UserRole r : roles.findAllByUserId(userId)) {
            result.add(r.getRole());
        }
        return result;
    }

    @Transactional
    public boolean grantIfMissing(UUID userId, RoleName role, UUID grantedBy) {
        if (roles.existsByUserIdAndRole(userId, role)) return false;
        UserRole r = new UserRole();
        r.setUserId(userId);
        r.setRole(role);
        r.setGrantedBy(grantedBy);
        try {
            roles.saveAndFlush(r);
            return true;
        } catch (DataIntegrityViolationException e) {
            // Concurrent grant won the race — that's fine.
            return false;
        }
    }

    @Transactional
    public boolean revoke(UUID userId, RoleName role) {
        return roles.deleteByUserIdAndRole(userId, role) > 0;
    }
}
