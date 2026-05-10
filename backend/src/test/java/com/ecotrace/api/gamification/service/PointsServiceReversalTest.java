package com.ecotrace.api.gamification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecotrace.api.config.properties.GamificationProperties;
import com.ecotrace.api.gamification.api.PointsAward;
import com.ecotrace.api.gamification.entity.PointsReason;
import com.ecotrace.api.gamification.entity.PointsTransaction;
import com.ecotrace.api.gamification.event.PointsAwarded;
import com.ecotrace.api.gamification.repository.PointsTransactionRepository;
import com.ecotrace.api.identity.api.UserPointsFacade;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

class PointsServiceReversalTest {

    private final PointsTransactionRepository transactions = Mockito.mock(PointsTransactionRepository.class);
    private final UserPointsFacade users = Mockito.mock(UserPointsFacade.class);
    private final LevelService levelService = Mockito.mock(LevelService.class);
    private final ApplicationEventPublisher events = Mockito.mock(ApplicationEventPublisher.class);

    private final GamificationProperties props = new GamificationProperties(
            new GamificationProperties.Points(10, 5, -10),
            Map.of("SMALL", BigDecimal.ONE));

    private final PointsService svc = new PointsService(transactions, users, levelService, props, events);

    @Test
    void reverses_collection_and_bonus_with_negative_ledger_entries() {
        UUID collectorId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        UUID pinId = UUID.randomUUID();

        PointsTransaction collectionTx = positiveTx(collectorId, collectionId, pinId, 10, PointsReason.COLLECTION);
        PointsTransaction bonusTx = positiveTx(reporterId, collectionId, pinId, 5, PointsReason.BONUS);

        when(transactions.findByCollectionIdAndReasonIn(eq(collectionId), any()))
                .thenReturn(List.of(collectionTx, bonusTx));
        when(transactions.findFirstByReversesTransactionId(any())).thenReturn(Optional.empty());
        when(transactions.saveAndFlush(any(PointsTransaction.class))).thenAnswer(inv -> {
            PointsTransaction t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        when(users.getTotalPoints(collectorId)).thenReturn(10L);
        when(users.getTotalPoints(reporterId)).thenReturn(5L);
        when(levelService.compute(0L)).thenReturn(1);

        List<PointsAward> awards = svc.reverseForCollection(collectionId);

        assertThat(awards).hasSize(2);
        assertThat(awards).allSatisfy(a -> assertThat(a.idempotent()).isFalse());
        assertThat(awards.get(0).delta()).isEqualTo(-10);
        assertThat(awards.get(1).delta()).isEqualTo(-5);
        verify(users).setPointsAndLevel(collectorId, 0L, 1);
        verify(users).setPointsAndLevel(reporterId, 0L, 1);

        ArgumentCaptor<PointsAwarded> ev = ArgumentCaptor.forClass(PointsAwarded.class);
        verify(events, times(2)).publishEvent(ev.capture());
        assertThat(ev.getAllValues()).allMatch(a -> a.reason().equals("REVERSAL"));
    }

    @Test
    void existing_reversal_row_is_treated_as_idempotent_no_op() {
        UUID collectorId = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        UUID pinId = UUID.randomUUID();
        PointsTransaction collectionTx = positiveTx(collectorId, collectionId, pinId, 10, PointsReason.COLLECTION);

        PointsTransaction existingReversal = new PointsTransaction();
        existingReversal.setId(UUID.randomUUID());
        existingReversal.setDelta(-10);
        existingReversal.setReversesTransactionId(collectionTx.getId());

        when(transactions.findByCollectionIdAndReasonIn(eq(collectionId), any()))
                .thenReturn(List.of(collectionTx));
        when(transactions.findFirstByReversesTransactionId(collectionTx.getId()))
                .thenReturn(Optional.of(existingReversal));
        when(users.getTotalPoints(collectorId)).thenReturn(0L);
        when(levelService.compute(0L)).thenReturn(1);

        List<PointsAward> awards = svc.reverseForCollection(collectionId);

        assertThat(awards).hasSize(1);
        assertThat(awards.get(0).idempotent()).isTrue();
        assertThat(awards.get(0).delta()).isEqualTo(-10);
        verify(transactions, never()).saveAndFlush(any(PointsTransaction.class));
        verify(users, never()).setPointsAndLevel(any(), anyLong(), Mockito.anyInt());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void race_loser_resolves_to_existing_reversal_via_constraint_violation() {
        UUID collectorId = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        UUID pinId = UUID.randomUUID();
        PointsTransaction collectionTx = positiveTx(collectorId, collectionId, pinId, 10, PointsReason.COLLECTION);
        PointsTransaction other = new PointsTransaction();
        other.setId(UUID.randomUUID());
        other.setDelta(-10);
        other.setReversesTransactionId(collectionTx.getId());

        when(transactions.findByCollectionIdAndReasonIn(eq(collectionId), any()))
                .thenReturn(List.of(collectionTx));
        when(transactions.findFirstByReversesTransactionId(collectionTx.getId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(other));
        when(transactions.saveAndFlush(any(PointsTransaction.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(users.getTotalPoints(collectorId)).thenReturn(0L);
        when(levelService.compute(0L)).thenReturn(1);

        List<PointsAward> awards = svc.reverseForCollection(collectionId);

        assertThat(awards).hasSize(1);
        assertThat(awards.get(0).idempotent()).isTrue();
        verify(users, never()).setPointsAndLevel(any(), anyLong(), Mockito.anyInt());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void no_originals_means_no_awards() {
        UUID collectionId = UUID.randomUUID();
        when(transactions.findByCollectionIdAndReasonIn(eq(collectionId), any()))
                .thenReturn(List.of());

        List<PointsAward> awards = svc.reverseForCollection(collectionId);

        assertThat(awards).isEmpty();
        verify(transactions, never()).saveAndFlush(any(PointsTransaction.class));
    }

    @Test
    void total_points_is_clamped_at_zero_when_balance_would_go_negative() {
        UUID collectorId = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        UUID pinId = UUID.randomUUID();
        PointsTransaction collectionTx = positiveTx(collectorId, collectionId, pinId, 10, PointsReason.COLLECTION);

        when(transactions.findByCollectionIdAndReasonIn(eq(collectionId), any()))
                .thenReturn(List.of(collectionTx));
        when(transactions.findFirstByReversesTransactionId(any())).thenReturn(Optional.empty());
        when(transactions.saveAndFlush(any(PointsTransaction.class))).thenAnswer(inv -> {
            PointsTransaction t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        // user already spent some bonus; balance is below the original delta
        when(users.getTotalPoints(collectorId)).thenReturn(3L);
        when(levelService.compute(0L)).thenReturn(1);

        svc.reverseForCollection(collectionId);

        verify(users).setPointsAndLevel(collectorId, 0L, 1);
    }

    private static PointsTransaction positiveTx(UUID userId, UUID collectionId,
                                                UUID pinId, int delta, PointsReason reason) {
        PointsTransaction tx = new PointsTransaction();
        tx.setId(UUID.randomUUID());
        tx.setUserId(userId);
        tx.setDelta(delta);
        tx.setReason(reason);
        tx.setCollectionId(collectionId);
        tx.setWastePointId(pinId);
        return tx;
    }
}
