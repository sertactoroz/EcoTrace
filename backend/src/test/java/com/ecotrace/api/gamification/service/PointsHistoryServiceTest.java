package com.ecotrace.api.gamification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.gamification.entity.PointsReason;
import com.ecotrace.api.gamification.entity.PointsTransaction;
import com.ecotrace.api.gamification.repository.PointsTransactionRepository;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Limit;

class PointsHistoryServiceTest {

    private final PointsTransactionRepository repo = Mockito.mock(PointsTransactionRepository.class);
    private final PointsHistoryService svc = new PointsHistoryService(repo);

    @Test
    void no_cursor_returns_first_page_with_no_next_when_under_limit() {
        UUID userId = UUID.randomUUID();
        List<PointsTransaction> rows = txns(3, OffsetDateTime.parse("2026-05-09T12:00:00Z"));
        when(repo.findPageByUserId(eq(userId), any(Limit.class))).thenReturn(rows);

        var resp = svc.history(userId, 20, null);

        assertThat(resp.items()).hasSize(3);
        assertThat(resp.nextCursor()).isNull();
        verify(repo, never()).findPageByUserIdBefore(any(), any(), any(), any());
    }

    @Test
    void returns_next_cursor_when_more_rows_exist() {
        UUID userId = UUID.randomUUID();
        // Request limit=2, repo returns 3 (the +1 lookahead) — last row trimmed, cursor built from 2nd row.
        List<PointsTransaction> rows = txns(3, OffsetDateTime.parse("2026-05-09T12:00:00Z"));
        ArgumentCaptor<Limit> limitCap = ArgumentCaptor.forClass(Limit.class);
        when(repo.findPageByUserId(eq(userId), limitCap.capture())).thenReturn(rows);

        var resp = svc.history(userId, 2, null);

        assertThat(limitCap.getValue().max()).isEqualTo(3);
        assertThat(resp.items()).hasSize(2);
        assertThat(resp.nextCursor()).isNotBlank();
    }

    @Test
    void cursor_round_trips_through_decode() {
        UUID userId = UUID.randomUUID();
        List<PointsTransaction> firstPage = txns(3, OffsetDateTime.parse("2026-05-09T12:00:00Z"));
        when(repo.findPageByUserId(eq(userId), any(Limit.class))).thenReturn(firstPage);

        String cursor = svc.history(userId, 2, null).nextCursor();

        ArgumentCaptor<OffsetDateTime> tsCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<UUID> idCap = ArgumentCaptor.forClass(UUID.class);
        when(repo.findPageByUserIdBefore(eq(userId), tsCap.capture(), idCap.capture(), any(Limit.class)))
                .thenReturn(List.of());

        svc.history(userId, 2, cursor);

        // Cursor should encode the LAST row of the trimmed page (item index 1).
        PointsTransaction expected = firstPage.get(1);
        assertThat(tsCap.getValue()).isEqualTo(expected.getCreatedAt());
        assertThat(idCap.getValue()).isEqualTo(expected.getId());
    }

    @Test
    void invalid_cursor_returns_invalid_request() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> svc.history(userId, 20, "not-a-cursor"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void limit_is_clamped_to_max() {
        UUID userId = UUID.randomUUID();
        ArgumentCaptor<Limit> cap = ArgumentCaptor.forClass(Limit.class);
        when(repo.findPageByUserId(eq(userId), cap.capture())).thenReturn(List.of());

        svc.history(userId, 10_000, null);

        // Service requests limit+1; max page size is 100 so we expect 101.
        assertThat(cap.getValue().max()).isEqualTo(101);
    }

    @Test
    void null_limit_uses_default_20() {
        UUID userId = UUID.randomUUID();
        ArgumentCaptor<Limit> cap = ArgumentCaptor.forClass(Limit.class);
        when(repo.findPageByUserId(eq(userId), cap.capture())).thenReturn(List.of());

        svc.history(userId, null, null);

        assertThat(cap.getValue().max()).isEqualTo(21);
    }

    private static List<PointsTransaction> txns(int count, OffsetDateTime base) {
        List<PointsTransaction> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PointsTransaction t = new PointsTransaction();
            t.setId(UUID.randomUUID());
            t.setUserId(UUID.randomUUID());
            t.setDelta(10);
            t.setReason(PointsReason.COLLECTION);
            setCreatedAt(t, base.minusMinutes(i).withOffsetSameInstant(ZoneOffset.UTC));
            out.add(t);
        }
        return out;
    }

    private static void setCreatedAt(PointsTransaction t, OffsetDateTime ts) {
        try {
            Field f = PointsTransaction.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(t, ts);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
