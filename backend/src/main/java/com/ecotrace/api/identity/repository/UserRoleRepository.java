package com.ecotrace.api.identity.repository;

import com.ecotrace.api.identity.entity.RoleName;
import com.ecotrace.api.identity.entity.UserRole;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    List<UserRole> findAllByUserId(UUID userId);

    boolean existsByUserIdAndRole(UUID userId, RoleName role);
}
