package com.ecotrace.api.collection.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecotrace.api.collection.entity.Collection;
import com.ecotrace.api.collection.entity.CollectionEvidence;
import com.ecotrace.api.collection.entity.CollectionStatus;
import com.ecotrace.api.collection.entity.PhotoKind;
import com.ecotrace.api.collection.repository.CollectionEvidenceRepository;
import com.ecotrace.api.collection.repository.CollectionRepository;
import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.config.properties.FraudProperties;
import com.ecotrace.api.gamification.api.PointsApi;
import com.ecotrace.api.gamification.api.PointsAward;
import com.ecotrace.api.media.api.MediaUrlResolver;
import com.ecotrace.api.waste.api.PinPointsContext;
import com.ecotrace.api.waste.api.WastePointFacade;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

class CollectionServiceVerifyTest {

    private final CollectionRepository collections = Mockito.mock(CollectionRepository.class);
    private final CollectionEvidenceRepository evidence = Mockito.mock(CollectionEvidenceRepository.class);
    private final WastePointFacade wastePoints = Mockito.mock(WastePointFacade.class);
    private final MediaUrlResolver media = Mockito.mock(MediaUrlResolver.class);
    private final PointsApi points = Mockito.mock(PointsApi.class);
    private final ApplicationEventPublisher events = Mockito.mock(ApplicationEventPublisher.class);

    private final FraudProperties fraudConfig = new FraudProperties(50, 30, 10, false);

    private final CollectionService svc = new CollectionService(
            collections, evidence, wastePoints, media, points, events, fraudConfig);

    @Test
    void verify_pays_reporter_bonus_when_reporter_distinct_from_collector() {
        UUID collectorId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Collection c = submittedCollection(collectorId);

        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        when(collections.save(any(Collection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(wastePoints.getPointsContext(c.getWastePointId()))
                .thenReturn(new PinPointsContext("plastic", BigDecimal.ONE, "SMALL", reporterId));
        when(points.awardForCollection(eq(collectorId), eq(c.getId()), eq(c.getWastePointId()), any(), any()))
                .thenReturn(new PointsAward(UUID.randomUUID(), 10, 10L, 1, false));
        when(points.awardReporterBonus(eq(reporterId), eq(c.getId()), eq(c.getWastePointId())))
                .thenReturn(new PointsAward(UUID.randomUUID(), 5, 5L, 1, false));
        when(evidence.findByCollectionId(c.getId())).thenReturn(List.of());

        svc.verify(moderatorId, c.getId());

        verify(points, times(1)).awardForCollection(eq(collectorId), eq(c.getId()), eq(c.getWastePointId()), any(), any());
        verify(points, times(1)).awardReporterBonus(eq(reporterId), eq(c.getId()), eq(c.getWastePointId()));
    }

    @Test
    void verify_skips_reporter_bonus_when_reporter_is_collector() {
        UUID userId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Collection c = submittedCollection(userId);

        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        when(collections.save(any(Collection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(wastePoints.getPointsContext(c.getWastePointId()))
                .thenReturn(new PinPointsContext("plastic", BigDecimal.ONE, "SMALL", userId));
        when(points.awardForCollection(eq(userId), eq(c.getId()), eq(c.getWastePointId()), any(), any()))
                .thenReturn(new PointsAward(UUID.randomUUID(), 10, 10L, 1, false));
        when(evidence.findByCollectionId(c.getId())).thenReturn(List.of());

        svc.verify(moderatorId, c.getId());

        verify(points, never()).awardReporterBonus(any(), any(), any());
    }

    @Test
    void verify_skips_reporter_bonus_when_reporter_is_null() {
        UUID collectorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Collection c = submittedCollection(collectorId);

        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        when(collections.save(any(Collection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(wastePoints.getPointsContext(c.getWastePointId()))
                .thenReturn(new PinPointsContext("plastic", BigDecimal.ONE, "SMALL", null));
        when(points.awardForCollection(eq(collectorId), eq(c.getId()), eq(c.getWastePointId()), any(), any()))
                .thenReturn(new PointsAward(UUID.randomUUID(), 10, 10L, 1, false));
        when(evidence.findByCollectionId(c.getId())).thenReturn(List.of());

        svc.verify(moderatorId, c.getId());

        verify(points, never()).awardReporterBonus(any(), any(), any());
    }

    @Test
    void verify_fails_fraud_gate_when_distance_exceeds_max() {
        UUID collectorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Collection c = submittedCollection(collectorId);
        c.setDistanceFromPinM(BigDecimal.valueOf(120.00));

        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        when(evidence.findByCollectionId(c.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> svc.verify(moderatorId, c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FRAUD_GATE_FAILED);

        verify(points, never()).awardForCollection(any(), any(), any(), any(), any());
        verify(collections, never()).save(any());
    }

    @Test
    void verify_fails_fraud_gate_when_dwell_too_short() {
        UUID collectorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Collection c = submittedCollection(collectorId);
        c.setDwellSeconds(5);

        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        when(evidence.findByCollectionId(c.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> svc.verify(moderatorId, c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FRAUD_GATE_FAILED);

        verify(points, never()).awardForCollection(any(), any(), any(), any(), any());
    }

    @Test
    void verify_fails_fraud_gate_when_after_photo_required_but_missing() {
        FraudProperties strict = new FraudProperties(50, 30, 10, true);
        CollectionService strictSvc = new CollectionService(
                collections, evidence, wastePoints, media, points, events, strict);

        UUID collectorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Collection c = submittedCollection(collectorId);

        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        when(evidence.findByCollectionId(c.getId()))
                .thenReturn(List.of(beforePhoto(c.getId())));

        assertThatThrownBy(() -> strictSvc.verify(moderatorId, c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FRAUD_GATE_FAILED);

        verify(points, never()).awardForCollection(any(), any(), any(), any(), any());
    }

    @Test
    void verify_passes_when_after_photo_required_and_present() {
        FraudProperties strict = new FraudProperties(50, 30, 10, true);
        CollectionService strictSvc = new CollectionService(
                collections, evidence, wastePoints, media, points, events, strict);

        UUID collectorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Collection c = submittedCollection(collectorId);

        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        when(collections.save(any(Collection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(evidence.findByCollectionId(c.getId()))
                .thenReturn(List.of(afterPhoto(c.getId())));
        when(wastePoints.getPointsContext(c.getWastePointId()))
                .thenReturn(new PinPointsContext("plastic", BigDecimal.ONE, "SMALL", null));
        when(points.awardForCollection(eq(collectorId), eq(c.getId()), eq(c.getWastePointId()), any(), any()))
                .thenReturn(new PointsAward(UUID.randomUUID(), 10, 10L, 1, false));

        strictSvc.verify(moderatorId, c.getId());

        verify(points, times(1)).awardForCollection(eq(collectorId), eq(c.getId()), eq(c.getWastePointId()), any(), any());
    }

    @Test
    void verify_fails_fraud_gate_when_velocity_exceeded() {
        UUID collectorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Collection c = submittedCollection(collectorId);

        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        when(evidence.findByCollectionId(c.getId())).thenReturn(List.of());
        when(collections.countByCollectorUserIdAndSubmittedAtAfter(eq(collectorId), any()))
                .thenReturn(15L);

        assertThatThrownBy(() -> svc.verify(moderatorId, c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FRAUD_GATE_FAILED);

        verify(points, never()).awardForCollection(any(), any(), any(), any(), any());
    }

    @Test
    void verify_velocity_does_not_count_self_when_recent_count_equals_max() {
        UUID collectorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Collection c = submittedCollection(collectorId);
        c.setSubmittedAt(OffsetDateTime.now().minusMinutes(5));

        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        when(collections.save(any(Collection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(evidence.findByCollectionId(c.getId())).thenReturn(List.of());
        when(collections.countByCollectorUserIdAndSubmittedAtAfter(eq(collectorId), any()))
                .thenReturn(11L);
        when(wastePoints.getPointsContext(c.getWastePointId()))
                .thenReturn(new PinPointsContext("plastic", BigDecimal.ONE, "SMALL", null));
        when(points.awardForCollection(eq(collectorId), eq(c.getId()), eq(c.getWastePointId()), any(), any()))
                .thenReturn(new PointsAward(UUID.randomUUID(), 10, 10L, 1, false));

        svc.verify(moderatorId, c.getId());

        verify(points, times(1)).awardForCollection(eq(collectorId), eq(c.getId()), eq(c.getWastePointId()), any(), any());
    }

    private static Collection submittedCollection(UUID collectorId) {
        Collection c = new Collection();
        c.setId(UUID.randomUUID());
        c.setWastePointId(UUID.randomUUID());
        c.setCollectorUserId(collectorId);
        c.setStatus(CollectionStatus.SUBMITTED);
        return c;
    }

    private static CollectionEvidence beforePhoto(UUID collectionId) {
        CollectionEvidence e = new CollectionEvidence();
        e.setCollectionId(collectionId);
        e.setStorageKey("before.jpg");
        e.setUrl("https://x/before.jpg");
        e.setKind(PhotoKind.BEFORE);
        return e;
    }

    private static CollectionEvidence afterPhoto(UUID collectionId) {
        CollectionEvidence e = new CollectionEvidence();
        e.setCollectionId(collectionId);
        e.setStorageKey("after.jpg");
        e.setUrl("https://x/after.jpg");
        e.setKind(PhotoKind.AFTER);
        return e;
    }
}
