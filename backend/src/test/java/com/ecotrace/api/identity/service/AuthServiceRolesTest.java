package com.ecotrace.api.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecotrace.api.config.properties.AuthProperties;
import com.ecotrace.api.identity.entity.RoleName;
import com.ecotrace.api.identity.entity.User;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AuthServiceRolesTest {

    private final UserRoleService userRoles = Mockito.mock(UserRoleService.class);

    @Test
    void plain_user_with_no_db_role_and_not_allowlisted_gets_only_USER() {
        AuthService svc = newService(List.of());
        User u = user("alice@example.com");
        when(userRoles.rolesFor(u.getId())).thenReturn(EnumSet.noneOf(RoleName.class));

        assertThat(svc.rolesFor(u)).containsExactly("USER");
        verify(userRoles, never()).grantIfMissing(any(), any(), any());
    }

    @Test
    void user_with_existing_DB_moderator_role_gets_moderator() {
        AuthService svc = newService(List.of());
        User u = user("mod@example.com");
        when(userRoles.rolesFor(u.getId())).thenReturn(EnumSet.of(RoleName.MODERATOR));

        assertThat(svc.rolesFor(u)).containsExactlyInAnyOrder("USER", "MODERATOR");
        verify(userRoles, never()).grantIfMissing(any(), any(), any());
    }

    @Test
    void allowlisted_user_without_DB_role_is_bootstrapped_and_granted_moderator() {
        AuthService svc = newService(List.of("BOOT@Example.COM"));
        User u = user("boot@example.com");
        when(userRoles.rolesFor(u.getId())).thenReturn(EnumSet.noneOf(RoleName.class));
        when(userRoles.grantIfMissing(u.getId(), RoleName.MODERATOR, null)).thenReturn(true);

        assertThat(svc.rolesFor(u)).containsExactlyInAnyOrder("USER", "MODERATOR");
        verify(userRoles, times(1)).grantIfMissing(u.getId(), RoleName.MODERATOR, null);
    }

    @Test
    void allowlisted_user_with_DB_role_does_not_attempt_grant() {
        AuthService svc = newService(List.of("mod@example.com"));
        User u = user("mod@example.com");
        when(userRoles.rolesFor(u.getId())).thenReturn(EnumSet.of(RoleName.MODERATOR));

        assertThat(svc.rolesFor(u)).containsExactlyInAnyOrder("USER", "MODERATOR");
        verify(userRoles, never()).grantIfMissing(any(), eq(RoleName.MODERATOR), any());
    }

    @Test
    void admin_role_is_propagated() {
        AuthService svc = newService(List.of());
        User u = user("root@example.com");
        when(userRoles.rolesFor(u.getId())).thenReturn(EnumSet.of(RoleName.ADMIN));

        assertThat(svc.rolesFor(u)).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    private AuthService newService(List<String> moderatorEmails) {
        return new AuthService(null, null, null, null, null, null,
                new AuthProperties(moderatorEmails), userRoles);
    }

    private static User user(String email) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        return u;
    }
}
