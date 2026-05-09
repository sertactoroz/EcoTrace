package com.ecotrace.api.security.principal;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedUser(UUID userId, String email, Set<String> roles) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
