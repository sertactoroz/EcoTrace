package com.ecotrace.api.collection.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecotrace.api.collection.dto.request.ReverseCollectionRequest;
import com.ecotrace.api.collection.entity.Collection;
import com.ecotrace.api.collection.entity.CollectionStatus;
import com.ecotrace.api.collection.event.CollectionReversed;
import com.ecotrace.api.collection.repository.CollectionEvidenceRepository;
import com.ecotrace.api.collection.repository.CollectionRepository;
import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.config.properties.FraudProperties;
import com.ecotrace.api.gamification.api.PointsApi;
import com.ecotrace.api.media.api.MediaUrlResolver;
import com.ecotrace.api.waste.api.WastePointFacade;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

class CollectionServiceReverseTest {

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
    void reverse_changes_status_and_reverses_points() {
        UUID adminId = UUID.randomUUID();
        Collection c = verifiedCollection();

        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        when(collections.save(any(Collection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(points.reverseForCollection(c.getId())).thenReturn(List.of());
        when(evidence.findByCollectionId(c.getId())).thenReturn(List.of());

        svc.reverse(adminId, c.getId(), new ReverseCollectionRequest("fraud detected"));

        verify(points, times(1)).reverseForCollection(c.getId());
        ArgumentCaptor<CollectionReversed> ev = ArgumentCaptor.forClass(CollectionReversed.class);
        verify(events).publishEvent(ev.capture());
        org.assertj.core.api.Assertions.assertThat(ev.getValue().reason()).isEqualTo("fraud detected");
        org.assertj.core.api.Assertions.assertThat(ev.getValue().reversedByUserId()).isEqualTo(adminId);
        org.assertj.core.api.Assertions.assertThat(c.getStatus()).isEqualTo(CollectionStatus.REVERSED);
        org.assertj.core.api.Assertions.assertThat(c.getReversedAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(c.getPointsAwarded()).isZero();
    }

    @Test
    void reverse_rejects_non_verified_state() {
        UUID adminId = UUID.randomUUID();
        Collection c = verifiedCollection();
        c.setStatus(CollectionStatus.SUBMITTED);

        when(collections.findById(c.getId())).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> svc.reverse(adminId, c.getId(),
                new ReverseCollectionRequest("nope")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATE);

        verify(points, never()).reverseForCollection(any());
    }

    @Test
    void reverse_is_idempotent_when_already_reversed() {
        UUID adminId = UUID.randomUUID();
        Collection c = verifiedCollection();
        c.setStatus(CollectionStatus.REVERSED);

        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        when(evidence.findByCollectionId(c.getId())).thenReturn(List.of());

        svc.reverse(adminId, c.getId(), new ReverseCollectionRequest("retry"));

        verify(points, never()).reverseForCollection(any());
        verify(events, never()).publishEvent(any());
        verify(collections, never()).save(any());
    }

    @Test
    void reverse_throws_not_found_for_missing_collection() {
        UUID adminId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(collections.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.reverse(adminId, id, new ReverseCollectionRequest("x")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    private static Collection verifiedCollection() {
        Collection c = new Collection();
        c.setId(UUID.randomUUID());
        c.setWastePointId(UUID.randomUUID());
        c.setCollectorUserId(UUID.randomUUID());
        c.setStatus(CollectionStatus.VERIFIED);
        c.setPointsAwarded(10);
        return c;
    }
}
