package com.ecotrace.api.identity.controller;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.identity.dto.request.RoleGrantRequest;
import com.ecotrace.api.identity.dto.response.UserRolesResponse;
import com.ecotrace.api.identity.entity.RoleName;
import com.ecotrace.api.identity.service.AdminUserService;
import com.ecotrace.api.security.annotation.CurrentUser;
import com.ecotrace.api.security.principal.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<UserRolesResponse> findByEmail(@RequestParam String email) {
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "email is required");
        }
        return ResponseEntity.ok(service.findByEmail(email));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserRolesResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping("/{id}/roles")
    public ResponseEntity<UserRolesResponse> grant(
            @CurrentUser AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody RoleGrantRequest req) {
        return ResponseEntity.ok(service.grant(actor.userId(), id, req.role()));
    }

    @DeleteMapping("/{id}/roles/{role}")
    public ResponseEntity<UserRolesResponse> revoke(
            @CurrentUser AuthenticatedUser actor,
            @PathVariable UUID id,
            @PathVariable RoleName role) {
        return ResponseEntity.ok(service.revoke(actor.userId(), id, role));
    }
}
