package com.ecotrace.api.gamification.service;

import com.ecotrace.api.config.properties.GamificationProperties;
import com.ecotrace.api.gamification.api.PointsApi;
import com.ecotrace.api.gamification.api.PointsAward;
import com.ecotrace.api.gamification.entity.PointsReason;
import com.ecotrace.api.gamification.entity.PointsTransaction;
import com.ecotrace.api.gamification.event.PointsAwarded;
import com.ecotrace.api.gamification.repository.PointsTransactionRepository;
import com.ecotrace.api.identity.api.UserPointsFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointsService implements PointsApi {

    private final PointsTransactionRepository transactions;
    private final UserPointsFacade users;
    private final LevelService levelService;
    private final GamificationProperties config;
    private final ApplicationEventPublisher events;

    public PointsService(PointsTransactionRepository transactions,
                         UserPointsFacade users,
                         LevelService levelService,
                         GamificationProperties config,
                         ApplicationEventPublisher events) {
        this.transactions = transactions;
        this.users = users;
        this.levelService = levelService;
        this.config = config;
        this.events = events;
    }

    @Override
    @Transactional
    public PointsAward awardForCollection(UUID userId, UUID collectionId, UUID wastePointId,
                                          BigDecimal categoryMultiplier, String volumeKey) {
        Optional<PointsTransaction> existing =
                transactions.findFirstByCollectionIdAndReason(collectionId, PointsReason.COLLECTION);
        if (existing.isPresent()) {
            PointsTransaction tx = existing.get();
            long total = users.getTotalPoints(userId);
            return new PointsAward(tx.getId(), tx.getDelta(), total, levelService.compute(total), true);
        }

        int delta = computeDelta(categoryMultiplier, volumeKey);

        PointsTransaction tx = new PointsTransaction();
        tx.setUserId(userId);
        tx.setDelta(delta);
        tx.setReason(PointsReason.COLLECTION);
        tx.setCollectionId(collectionId);
        tx.setWastePointId(wastePointId);
        try {
            tx = transactions.saveAndFlush(tx);
        } catch (DataIntegrityViolationException e) {
            PointsTransaction other = transactions
                    .findFirstByCollectionIdAndReason(collectionId, PointsReason.COLLECTION)
                    .orElseThrow(() -> e);
            long total = users.getTotalPoints(userId);
            return new PointsAward(other.getId(), other.getDelta(), total,
                    levelService.compute(total), true);
        }

        long newTotal = users.getTotalPoints(userId) + delta;
        int newLevel = levelService.compute(newTotal);
        users.setPointsAndLevel(userId, newTotal, newLevel);

        events.publishEvent(new PointsAwarded(
                tx.getId(), userId, collectionId, wastePointId,
                delta, newTotal, newLevel, PointsReason.COLLECTION.name(), OffsetDateTime.now()));

        return new PointsAward(tx.getId(), delta, newTotal, newLevel, false);
    }

    @Override
    @Transactional
    public PointsAward awardReporterBonus(UUID reporterUserId, UUID collectionId, UUID wastePointId) {
        Optional<PointsTransaction> existing =
                transactions.findFirstByCollectionIdAndReason(collectionId, PointsReason.BONUS);
        if (existing.isPresent()) {
            PointsTransaction tx = existing.get();
            long total = users.getTotalPoints(reporterUserId);
            return new PointsAward(tx.getId(), tx.getDelta(), total, levelService.compute(total), true);
        }

        int delta = config.points().successfulReportBonus();
        if (delta == 0) {
            long total = users.getTotalPoints(reporterUserId);
            return new PointsAward(null, 0, total, levelService.compute(total), true);
        }

        PointsTransaction tx = new PointsTransaction();
        tx.setUserId(reporterUserId);
        tx.setDelta(delta);
        tx.setReason(PointsReason.BONUS);
        tx.setCollectionId(collectionId);
        tx.setWastePointId(wastePointId);
        try {
            tx = transactions.saveAndFlush(tx);
        } catch (DataIntegrityViolationException e) {
            PointsTransaction other = transactions
                    .findFirstByCollectionIdAndReason(collectionId, PointsReason.BONUS)
                    .orElseThrow(() -> e);
            long total = users.getTotalPoints(reporterUserId);
            return new PointsAward(other.getId(), other.getDelta(), total,
                    levelService.compute(total), true);
        }

        long newTotal = users.getTotalPoints(reporterUserId) + delta;
        int newLevel = levelService.compute(newTotal);
        users.setPointsAndLevel(reporterUserId, newTotal, newLevel);

        events.publishEvent(new PointsAwarded(
                tx.getId(), reporterUserId, collectionId, wastePointId,
                delta, newTotal, newLevel, PointsReason.BONUS.name(), OffsetDateTime.now()));

        return new PointsAward(tx.getId(), delta, newTotal, newLevel, false);
    }

    @Override
    @Transactional
    public List<PointsAward> reverseForCollection(UUID collectionId) {
        List<PointsTransaction> originals = transactions.findByCollectionIdAndReasonIn(
                collectionId, List.of(PointsReason.COLLECTION, PointsReason.BONUS));

        List<PointsAward> awards = new ArrayList<>();
        for (PointsTransaction original : originals) {
            awards.add(reverseSingle(original));
        }
        return awards;
    }

    private PointsAward reverseSingle(PointsTransaction original) {
        Optional<PointsTransaction> existing =
                transactions.findFirstByReversesTransactionId(original.getId());
        if (existing.isPresent()) {
            PointsTransaction tx = existing.get();
            long total = users.getTotalPoints(original.getUserId());
            return new PointsAward(tx.getId(), tx.getDelta(), total,
                    levelService.compute(total), true);
        }

        int delta = -original.getDelta();

        PointsTransaction tx = new PointsTransaction();
        tx.setUserId(original.getUserId());
        tx.setDelta(delta);
        tx.setReason(PointsReason.REVERSAL);
        tx.setCollectionId(original.getCollectionId());
        tx.setWastePointId(original.getWastePointId());
        tx.setReversesTransactionId(original.getId());
        try {
            tx = transactions.saveAndFlush(tx);
        } catch (DataIntegrityViolationException e) {
            PointsTransaction other = transactions
                    .findFirstByReversesTransactionId(original.getId())
                    .orElseThrow(() -> e);
            long total = users.getTotalPoints(original.getUserId());
            return new PointsAward(other.getId(), other.getDelta(), total,
                    levelService.compute(total), true);
        }

        long newTotal = Math.max(0L, users.getTotalPoints(original.getUserId()) + delta);
        int newLevel = levelService.compute(newTotal);
        users.setPointsAndLevel(original.getUserId(), newTotal, newLevel);

        events.publishEvent(new PointsAwarded(
                tx.getId(), original.getUserId(), original.getCollectionId(),
                original.getWastePointId(),
                delta, newTotal, newLevel, PointsReason.REVERSAL.name(), OffsetDateTime.now()));

        return new PointsAward(tx.getId(), delta, newTotal, newLevel, false);
    }

    private int computeDelta(BigDecimal categoryMultiplier, String volumeKey) {
        BigDecimal volumeMultiplier = volumeMultiplier(volumeKey);
        BigDecimal base = BigDecimal.valueOf(config.points().baseCollection());
        return base.multiply(categoryMultiplier)
                .multiply(volumeMultiplier)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    private BigDecimal volumeMultiplier(String volumeKey) {
        if (volumeKey == null || config.volumeMultipliers() == null) return BigDecimal.ONE;
        BigDecimal m = config.volumeMultipliers().get(volumeKey.toUpperCase(Locale.ROOT));
        return m == null ? BigDecimal.ONE : m;
    }
}
