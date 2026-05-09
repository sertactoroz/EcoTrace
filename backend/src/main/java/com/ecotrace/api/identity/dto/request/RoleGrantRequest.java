package com.ecotrace.api.identity.dto.request;

import com.ecotrace.api.identity.entity.RoleName;
import jakarta.validation.constraints.NotNull;

public record RoleGrantRequest(@NotNull RoleName role) {
}
