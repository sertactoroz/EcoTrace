package com.ecotrace.api.identity.repository;

import com.ecotrace.api.identity.entity.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    List<User> findAllByIdIn(Collection<UUID> ids);

    List<User> findAllByTotalPointsGreaterThan(long threshold);
}
