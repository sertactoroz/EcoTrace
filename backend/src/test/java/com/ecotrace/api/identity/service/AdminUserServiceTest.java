package com.ecotrace.api.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.identity.entity.RoleName;
import com.ecotrace.api.identity.entity.User;
import com.ecotrace.api.identity.repository.UserRepository;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AdminUserServiceTest {

    private final UserRepository users = Mockito.mock(UserRepository.class);
    private final UserRoleService userRoles = Mockito.mock(UserRoleService.class);
    private final AdminUserService svc = new AdminUserService(users, userRoles);

    @Test
    void grant_moderator_succeeds_and_returns_updated_roles() {
        UUID actor = UUID.randomUUID();
        User target = user("target@example.com");
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRoles.grantIfMissing(target.getId(), RoleName.MODERATOR, actor)).thenReturn(true);
        when(userRoles.rolesFor(target.getId())).thenReturn(EnumSet.of(RoleName.MODERATOR));

        var resp = svc.grant(actor, target.getId(), RoleName.MODERATOR);

        assertThat(resp.roles()).containsExactly(RoleName.MODERATOR);
        verify(userRoles, times(1)).grantIfMissing(target.getId(), RoleName.MODERATOR, actor);
    }

    @Test
    void grant_admin_via_api_is_rejected() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        assertThatThrownBy(() -> svc.grant(actor, target, RoleName.ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(userRoles, never()).grantIfMissing(any(), any(), any());
    }

    @Test
    void grant_on_unknown_user_returns_not_found() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(users.findById(target)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.grant(actor, target, RoleName.MODERATOR))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void revoke_moderator_succeeds() {
        UUID actor = UUID.randomUUID();
        User target = user("target@example.com");
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRoles.revoke(target.getId(), RoleName.MODERATOR)).thenReturn(true);
        when(userRoles.rolesFor(target.getId())).thenReturn(EnumSet.noneOf(RoleName.class));

        var resp = svc.revoke(actor, target.getId(), RoleName.MODERATOR);

        assertThat(resp.roles()).isEmpty();
        verify(userRoles, times(1)).revoke(target.getId(), RoleName.MODERATOR);
    }

    @Test
    void self_revoke_of_admin_is_rejected() {
        UUID self = UUID.randomUUID();

        assertThatThrownBy(() -> svc.revoke(self, self, RoleName.ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(userRoles, never()).revoke(any(), eq(RoleName.ADMIN));
    }

    @Test
    void revoking_admin_from_other_user_succeeds() {
        UUID actor = UUID.randomUUID();
        User target = user("other@example.com");
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRoles.revoke(target.getId(), RoleName.ADMIN)).thenReturn(true);
        when(userRoles.rolesFor(target.getId())).thenReturn(EnumSet.noneOf(RoleName.class));

        var resp = svc.revoke(actor, target.getId(), RoleName.ADMIN);

        assertThat(resp.roles()).isEmpty();
    }

    @Test
    void find_by_email_returns_roles() {
        User target = user("found@example.com");
        when(users.findByEmail("found@example.com")).thenReturn(Optional.of(target));
        when(userRoles.rolesFor(target.getId())).thenReturn(EnumSet.of(RoleName.MODERATOR));

        var resp = svc.findByEmail("found@example.com");

        assertThat(resp.email()).isEqualTo("found@example.com");
        assertThat(resp.roles()).containsExactly(RoleName.MODERATOR);
    }

    @Test
    void find_by_unknown_email_returns_not_found() {
        when(users.findByEmail("nope@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.findByEmail("nope@example.com"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private static User user(String email) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(email);
        return u;
    }
}
