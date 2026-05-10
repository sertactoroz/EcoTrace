package com.ecotrace.api.collection.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecotrace.api.collection.entity.Collection;
import com.ecotrace.api.collection.entity.CollectionStatus;
import com.ecotrace.api.collection.repository.CollectionEvidenceRepository;
import com.ecotrace.api.collection.repository.CollectionRepository;
import com.ecotrace.api.gamification.api.PointsApi;
import com.ecotrace.api.gamification.api.PointsAward;
import com.ecotrace.api.media.api.MediaUrlResolver;
import com.ecotrace.api.waste.api.PinPointsContext;
import com.ecotrace.api.waste.api.WastePointFacade;
import java.math.BigDecimal;
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

    private final CollectionService svc = new CollectionService(
            collections, evidence, wastePoints, media, points, events);

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

    private static Collection submittedCollection(UUID collectorId) {
        Collection c = new Collection();
        c.setId(UUID.randomUUID());
        c.setWastePointId(UUID.randomUUID());
        c.setCollectorUserId(collectorId);
        c.setStatus(CollectionStatus.SUBMITTED);
        return c;
    }
}
