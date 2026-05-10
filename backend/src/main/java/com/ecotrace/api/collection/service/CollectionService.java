package com.ecotrace.api.collection.service;

import com.ecotrace.api.collection.dto.request.RejectCollectionRequest;
import com.ecotrace.api.collection.dto.request.ReverseCollectionRequest;
import com.ecotrace.api.collection.dto.request.SubmitCollectionRequest;
import com.ecotrace.api.collection.dto.response.CollectionResponse;
import com.ecotrace.api.collection.entity.Collection;
import com.ecotrace.api.collection.entity.CollectionEvidence;
import com.ecotrace.api.collection.entity.CollectionStatus;
import com.ecotrace.api.collection.entity.PhotoKind;
import com.ecotrace.api.collection.event.CollectionClaimed;
import com.ecotrace.api.collection.event.CollectionRejected;
import com.ecotrace.api.collection.event.CollectionReversed;
import com.ecotrace.api.collection.event.CollectionSubmitted;
import com.ecotrace.api.collection.event.CollectionVerified;
import com.ecotrace.api.collection.domain.FraudGate;
import com.ecotrace.api.collection.domain.GeoDistance;
import com.ecotrace.api.collection.repository.CollectionEvidenceRepository;
import com.ecotrace.api.collection.repository.CollectionRepository;
import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.config.properties.FraudProperties;
import com.ecotrace.api.gamification.api.PointsApi;
import com.ecotrace.api.gamification.api.PointsAward;
import com.ecotrace.api.media.api.MediaUrlResolver;
import com.ecotrace.api.waste.api.ClaimedPin;
import com.ecotrace.api.waste.api.PinLocation;
import com.ecotrace.api.waste.api.PinPointsContext;
import com.ecotrace.api.waste.api.WastePointFacade;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionService {

    private static final Duration CLAIM_TTL = Duration.ofHours(24);
    private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

    private final CollectionRepository collections;
    private final CollectionEvidenceRepository evidence;
    private final WastePointFacade wastePoints;
    private final MediaUrlResolver media;
    private final PointsApi points;
    private final ApplicationEventPublisher events;
    private final FraudProperties fraudConfig;
    private final FraudGate fraudGate;

    public CollectionService(CollectionRepository collections,
                             CollectionEvidenceRepository evidence,
                             WastePointFacade wastePoints,
                             MediaUrlResolver media,
                             PointsApi points,
                             ApplicationEventPublisher events,
                             FraudProperties fraudConfig) {
        this.collections = collections;
        this.evidence = evidence;
        this.wastePoints = wastePoints;
        this.media = media;
        this.points = points;
        this.events = events;
        this.fraudConfig = fraudConfig;
        this.fraudGate = new FraudGate(fraudConfig);
    }

    @Transactional
    public CollectionResponse claim(UUID userId, UUID wastePointId) {
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(CLAIM_TTL);
        ClaimedPin pin = wastePoints.claimForUser(wastePointId, userId, expiresAt);

        Collection c = new Collection();
        c.setWastePointId(pin.pinId());
        c.setCollectorUserId(userId);
        c.setStatus(CollectionStatus.CLAIMED);
        c.setClaimedAt(OffsetDateTime.now());

        try {
            c = collections.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ALREADY_CLAIMED,
                    "An open collection already exists for this pin");
        }

        events.publishEvent(CollectionClaimed.now(c.getId(), pin.pinId(), userId, expiresAt));
        return toResponse(c, expiresAt, List.of());
    }

    @Transactional
    public CollectionResponse submit(UUID userId, UUID collectionId, SubmitCollectionRequest req) {
        Collection c = collections.findById(collectionId)
                .orElseThrow(() -> new EntityNotFoundException("Collection " + collectionId));
        if (!userId.equals(c.getCollectorUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not your collection");
        }
        if (c.getStatus() != CollectionStatus.CLAIMED) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "Cannot submit (status=" + c.getStatus() + ")");
        }

        c.setStatus(CollectionStatus.SUBMITTED);
        c.setSubmittedAt(OffsetDateTime.now());
        c.setNotes(req.notes());
        c.setDwellSeconds(req.dwellSeconds());
        if (req.collectorLatitude() != null && req.collectorLongitude() != null) {
            Point p = GEO.createPoint(new Coordinate(req.collectorLongitude(), req.collectorLatitude()));
            p.setSRID(4326);
            c.setCollectorLocation(p);

            PinLocation pin = wastePoints.getLocation(c.getWastePointId());
            double meters = GeoDistance.haversineMeters(
                    pin.latitude(), pin.longitude(),
                    req.collectorLatitude(), req.collectorLongitude());
            c.setDistanceFromPinM(BigDecimal.valueOf(meters).setScale(2, RoundingMode.HALF_UP));
        }
        c = collections.save(c);
        UUID savedId = c.getId();

        List<CollectionEvidence> persisted = req.photos().stream()
                .map(photo -> persistEvidence(savedId, photo))
                .toList();

        events.publishEvent(CollectionSubmitted.now(c.getId(), c.getWastePointId(),
                userId, persisted.size()));
        return toResponse(c, null, persisted);
    }

    @Transactional(readOnly = true)
    public CollectionResponse get(UUID userId, UUID collectionId) {
        Collection c = collections.findById(collectionId)
                .orElseThrow(() -> new EntityNotFoundException("Collection " + collectionId));
        if (!userId.equals(c.getCollectorUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not your collection");
        }
        return toResponse(c, null, evidence.findByCollectionId(collectionId));
    }

    @Transactional
    public CollectionResponse verify(UUID moderatorId, UUID collectionId) {
        Collection c = collections.findById(collectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Collection " + collectionId));
        if (c.getStatus() != CollectionStatus.SUBMITTED) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "Cannot verify (status=" + c.getStatus() + ")");
        }

        List<CollectionEvidence> evidenceRows = evidence.findByCollectionId(collectionId);
        OffsetDateTime velocityWindow = OffsetDateTime.now().minusHours(1);
        long recentSubmissions = collections.countByCollectorUserIdAndSubmittedAtAfter(
                c.getCollectorUserId(), velocityWindow);
        // Don't count this submission against itself.
        long otherSubmissions = c.getSubmittedAt() != null && c.getSubmittedAt().isAfter(velocityWindow)
                ? Math.max(0, recentSubmissions - 1)
                : recentSubmissions;
        fraudGate.evaluate(c, evidenceRows, otherSubmissions).ifPresent(failure -> {
            throw new BusinessException(ErrorCode.FRAUD_GATE_FAILED,
                    failure.reason().name() + ": " + failure.detail());
        });

        PinPointsContext ctx = wastePoints.getPointsContext(c.getWastePointId());
        PointsAward award = points.awardForCollection(
                c.getCollectorUserId(), c.getId(), c.getWastePointId(),
                ctx.categoryMultiplier(), ctx.volumeKey());

        UUID reporter = ctx.reportedByUserId();
        if (reporter != null && !reporter.equals(c.getCollectorUserId())) {
            points.awardReporterBonus(reporter, c.getId(), c.getWastePointId());
        }

        c.setStatus(CollectionStatus.VERIFIED);
        c.setVerifiedAt(OffsetDateTime.now());
        c.setReviewedByUserId(moderatorId);
        c.setPointsAwarded(award.delta());
        c = collections.save(c);

        wastePoints.markVerified(c.getWastePointId(), c.getId());

        events.publishEvent(new CollectionVerified(
                c.getId(), c.getWastePointId(), c.getCollectorUserId(),
                moderatorId, award.delta(), c.getVerifiedAt()));

        return toResponse(c, null, evidence.findByCollectionId(collectionId));
    }

    @Transactional
    public CollectionResponse reject(UUID moderatorId, UUID collectionId, RejectCollectionRequest req) {
        Collection c = collections.findById(collectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Collection " + collectionId));
        if (c.getStatus() != CollectionStatus.SUBMITTED) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "Cannot reject (status=" + c.getStatus() + ")");
        }

        c.setStatus(CollectionStatus.REJECTED);
        c.setRejectedAt(OffsetDateTime.now());
        c.setReviewedByUserId(moderatorId);
        c.setRejectionReason(req.reason());
        c = collections.save(c);

        wastePoints.markRejected(c.getWastePointId());

        events.publishEvent(new CollectionRejected(
                c.getId(), c.getWastePointId(), c.getCollectorUserId(),
                moderatorId, req.reason(), c.getRejectedAt()));

        return toResponse(c, null, evidence.findByCollectionId(collectionId));
    }

    @Transactional
    public CollectionResponse reverse(UUID adminId, UUID collectionId, ReverseCollectionRequest req) {
        Collection c = collections.findById(collectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Collection " + collectionId));
        if (c.getStatus() == CollectionStatus.REVERSED) {
            return toResponse(c, null, evidence.findByCollectionId(collectionId));
        }
        if (c.getStatus() != CollectionStatus.VERIFIED) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "Cannot reverse (status=" + c.getStatus() + ")");
        }

        points.reverseForCollection(c.getId());

        c.setStatus(CollectionStatus.REVERSED);
        c.setReversedAt(OffsetDateTime.now());
        c.setReversedByUserId(adminId);
        c.setReversalReason(req.reason());
        c.setPointsAwarded(0);
        c = collections.save(c);

        events.publishEvent(new CollectionReversed(
                c.getId(), c.getWastePointId(), c.getCollectorUserId(),
                adminId, req.reason(), c.getReversedAt()));

        return toResponse(c, null, evidence.findByCollectionId(collectionId));
    }

    private CollectionEvidence persistEvidence(UUID collectionId,
                                               SubmitCollectionRequest.EvidencePhoto photo) {
        CollectionEvidence e = new CollectionEvidence();
        e.setCollectionId(collectionId);
        e.setStorageKey(photo.storageKey());
        e.setUrl(media.publicUrl(photo.storageKey()));
        e.setKind(parseKind(photo.kind()));
        return evidence.save(e);
    }

    private static PhotoKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) return PhotoKind.AFTER;
        try {
            return PhotoKind.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Invalid photo kind: " + raw);
        }
    }

    private static CollectionResponse toResponse(Collection c,
                                                 OffsetDateTime claimExpiresAt,
                                                 List<CollectionEvidence> evidenceRows) {
        List<CollectionResponse.EvidenceItem> items = evidenceRows.stream()
                .map(e -> new CollectionResponse.EvidenceItem(
                        e.getId(), e.getStorageKey(), e.getUrl(), e.getKind().name()))
                .toList();
        return new CollectionResponse(
                c.getId(),
                c.getWastePointId(),
                c.getCollectorUserId(),
                c.getStatus(),
                c.getClaimedAt(),
                claimExpiresAt,
                c.getSubmittedAt(),
                c.getNotes(),
                items,
                c.getPointsAwarded());
    }
}
