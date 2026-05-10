package com.ecotrace.api.gamification.repository;

import com.ecotrace.api.gamification.entity.PointsReason;
import com.ecotrace.api.gamification.entity.PointsTransaction;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointsTransactionRepository extends JpaRepository<PointsTransaction, UUID> {

    Optional<PointsTransaction> findFirstByCollectionIdAndReason(UUID collectionId, PointsReason reason);

    List<PointsTransaction> findByCollectionIdAndReasonIn(UUID collectionId, List<PointsReason> reasons);

    Optional<PointsTransaction> findFirstByReversesTransactionId(UUID reversesTransactionId);

    @Query("""
            SELECT t FROM PointsTransaction t
            WHERE t.userId = :userId
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<PointsTransaction> findPageByUserId(@Param("userId") UUID userId, Limit limit);

    @Query("""
            SELECT t FROM PointsTransaction t
            WHERE t.userId = :userId
              AND (t.createdAt < :beforeTs
                   OR (t.createdAt = :beforeTs AND t.id < :beforeId))
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<PointsTransaction> findPageByUserIdBefore(
            @Param("userId") UUID userId,
            @Param("beforeTs") OffsetDateTime beforeTs,
            @Param("beforeId") UUID beforeId,
            Limit limit);
}
