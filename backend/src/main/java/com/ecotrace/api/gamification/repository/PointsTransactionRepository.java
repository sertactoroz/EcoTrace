package com.ecotrace.api.gamification.repository;

import com.ecotrace.api.gamification.entity.PointsReason;
import com.ecotrace.api.gamification.entity.PointsTransaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointsTransactionRepository extends JpaRepository<PointsTransaction, UUID> {

    Optional<PointsTransaction> findFirstByCollectionIdAndReason(UUID collectionId, PointsReason reason);
}
