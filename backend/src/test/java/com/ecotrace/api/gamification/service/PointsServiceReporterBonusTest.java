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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

class PointsServiceReporterBonusTest {

    private final PointsTransactionRepository transactions = Mockito.mock(PointsTransactionRepository.class);
    private final UserPointsFacade users = Mockito.mock(UserPointsFacade.class);
    private final LevelService levelService = Mockito.mock(LevelService.class);
    private final ApplicationEventPublisher events = Mockito.mock(ApplicationEventPublisher.class);

    private final GamificationProperties props = new GamificationProperties(
            new GamificationProperties.Points(10, 5, -10),
            Map.of("SMALL", BigDecimal.ONE));

    private final PointsService svc = new PointsService(transactions, users, levelService, props, events);

    @Test
    void first_award_inserts_ledger_row_and_updates_user_total() {
        UUID reporter = UUID.randomUUID();
        UUID collection = UUID.randomUUID();
        UUID pin = UUID.randomUUID();
        when(transactions.findFirstByCollectionIdAndReason(collection, PointsReason.BONUS))
                .thenReturn(Optional.empty());
        when(transactions.saveAndFlush(any(PointsTransaction.class))).thenAnswer(inv -> {
            PointsTransaction tx = inv.getArgument(0);
            tx.setId(UUID.randomUUID());
            return tx;
        });
        when(users.getTotalPoints(reporter)).thenReturn(20L);
        when(levelService.compute(25L)).thenReturn(1);

        PointsAward award = svc.awardReporterBonus(reporter, collection, pin);

        assertThat(award.idempotent()).isFalse();
        assertThat(award.delta()).isEqualTo(5);
        assertThat(award.newTotalPoints()).isEqualTo(25L);
        verify(users).setPointsAndLevel(reporter, 25L, 1);
        ArgumentCaptor<PointsAwarded> ev = ArgumentCaptor.forClass(PointsAwarded.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().reason()).isEqualTo("BONUS");
        assertThat(ev.getValue().delta()).isEqualTo(5);
    }

    @Test
    void existing_bonus_row_is_treated_as_idempotent_no_op() {
        UUID reporter = UUID.randomUUID();
        UUID collection = UUID.randomUUID();
        UUID pin = UUID.randomUUID();
        PointsTransaction existing = new PointsTransaction();
        existing.setId(UUID.randomUUID());
        existing.setDelta(5);
        when(transactions.findFirstByCollectionIdAndReason(collection, PointsReason.BONUS))
                .thenReturn(Optional.of(existing));
        when(users.getTotalPoints(reporter)).thenReturn(50L);
        when(levelService.compute(50L)).thenReturn(2);

        PointsAward award = svc.awardReporterBonus(reporter, collection, pin);

        assertThat(award.idempotent()).isTrue();
        assertThat(award.delta()).isEqualTo(5);
        assertThat(award.newTotalPoints()).isEqualTo(50L);
        verify(transactions, never()).saveAndFlush(any(PointsTransaction.class));
        verify(users, never()).setPointsAndLevel(any(), anyLong(), Mockito.anyInt());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void race_loser_resolves_to_existing_row_via_constraint_violation() {
        UUID reporter = UUID.randomUUID();
        UUID collection = UUID.randomUUID();
        UUID pin = UUID.randomUUID();
        PointsTransaction other = new PointsTransaction();
        other.setId(UUID.randomUUID());
        other.setDelta(5);

        when(transactions.findFirstByCollectionIdAndReason(collection, PointsReason.BONUS))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(other));
        when(transactions.saveAndFlush(any(PointsTransaction.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(users.getTotalPoints(reporter)).thenReturn(40L);
        when(levelService.compute(40L)).thenReturn(2);

        PointsAward award = svc.awardReporterBonus(reporter, collection, pin);

        assertThat(award.idempotent()).isTrue();
        assertThat(award.delta()).isEqualTo(5);
        verify(users, never()).setPointsAndLevel(eq(reporter), anyLong(), Mockito.anyInt());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void zero_bonus_config_skips_ledger_write() {
        GamificationProperties zeroBonus = new GamificationProperties(
                new GamificationProperties.Points(10, 0, -10),
                Map.of());
        PointsService svc0 = new PointsService(transactions, users, levelService, zeroBonus, events);

        UUID reporter = UUID.randomUUID();
        UUID collection = UUID.randomUUID();
        UUID pin = UUID.randomUUID();
        when(transactions.findFirstByCollectionIdAndReason(collection, PointsReason.BONUS))
                .thenReturn(Optional.empty());
        when(users.getTotalPoints(reporter)).thenReturn(40L);
        when(levelService.compute(40L)).thenReturn(2);

        PointsAward award = svc0.awardReporterBonus(reporter, collection, pin);

        assertThat(award.idempotent()).isTrue();
        assertThat(award.delta()).isZero();
        verify(transactions, never()).saveAndFlush(any(PointsTransaction.class));
        verify(events, never()).publishEvent(any());
    }
}
