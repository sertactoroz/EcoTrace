package com.ecotrace.api.identity.dto.response;

import com.ecotrace.api.identity.entity.RoleName;
import java.util.Set;
import java.util.UUID;

public record UserRolesResponse(UUID userId, String email, String displayName, Set<RoleName> roles) {
}
