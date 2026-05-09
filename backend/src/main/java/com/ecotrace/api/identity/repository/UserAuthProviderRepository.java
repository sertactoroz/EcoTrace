package com.ecotrace.api.identity.repository;

import com.ecotrace.api.identity.entity.AuthProvider;
import com.ecotrace.api.identity.entity.UserAuthProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuthProviderRepository extends JpaRepository<UserAuthProvider, UUID> {
    Optional<UserAuthProvider> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);
}
