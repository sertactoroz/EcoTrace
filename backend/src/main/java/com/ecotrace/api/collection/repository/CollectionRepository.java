package com.ecotrace.api.collection.repository;

import com.ecotrace.api.collection.entity.Collection;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionRepository extends JpaRepository<Collection, UUID> {

    long countByCollectorUserIdAndSubmittedAtAfter(UUID collectorUserId, OffsetDateTime since);
}
