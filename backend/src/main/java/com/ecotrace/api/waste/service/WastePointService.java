package com.ecotrace.api.waste.service;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.common.util.GeoHashUtil;
import com.ecotrace.api.waste.api.ClaimedPin;
import com.ecotrace.api.waste.api.PinLocation;
import com.ecotrace.api.waste.api.PinPointsContext;
import com.ecotrace.api.waste.api.WastePointFacade;
import com.ecotrace.api.waste.dto.request.CreateWastePointRequest;
import com.ecotrace.api.waste.dto.response.MapResponse;
import com.ecotrace.api.waste.dto.response.WastePointResponse;
import com.ecotrace.api.waste.entity.WasteCategory;
import com.ecotrace.api.waste.entity.WastePoint;
import com.ecotrace.api.waste.entity.WastePointStatus;
import com.ecotrace.api.waste.event.WastePointCreated;
import com.ecotrace.api.waste.exception.WastePointNotFoundException;
import com.ecotrace.api.waste.repository.WasteCategoryRepository;
import com.ecotrace.api.waste.repository.WastePointRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WastePointService implements WastePointFacade {

    private static final Set<WastePointStatus> CLAIMABLE_STATUSES =
            Set.of(WastePointStatus.ACTIVE, WastePointStatus.PENDING_REVIEW);

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 2000;
    private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

    private final WastePointRepository wastePoints;
    private final WasteCategoryRepository categories;
    private final ApplicationEventPublisher events;

    public WastePointService(WastePointRepository wastePoints,
                             WasteCategoryRepository categories,
                             ApplicationEventPublisher events) {
        this.wastePoints = wastePoints;
        this.categories = categories;
        this.events = events;
    }

    @Transactional
    public WastePointResponse create(UUID reporterId, CreateWastePointRequest req) {
        WasteCategory cat = categories.findByCode(req.categoryCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "Unknown category: " + req.categoryCode()));

        Point point = GEO.createPoint(new Coordinate(req.longitude(), req.latitude()));
        point.setSRID(4326);

        WastePoint wp = new WastePoint();
        wp.setId(UUID.randomUUID());
        wp.setReportedByUserId(reporterId);
        wp.setCategoryId(cat.getId());
        wp.setLocation(point);
        wp.setLocationGeohash(GeoHashUtil.encode(req.latitude(), req.longitude(), 7));
        wp.setAddressText(req.addressText());
        wp.setRegionCode(req.regionCode());
        wp.setEstimatedVolume(req.estimatedVolume());
        wp.setDescription(req.description());
        wp.setStatus(WastePointStatus.PENDING_REVIEW);
        wp.setReportsCount(1);

        wp = wastePoints.save(wp);
        events.publishEvent(WastePointCreated.now(wp.getId(), reporterId, cat.getCode()));
        return toResponse(wp, cat.getCode());
    }

    @Transactional(readOnly = true)
    public WastePointResponse get(UUID id) {
        WastePoint wp = wastePoints.findById(id).orElseThrow(() -> new WastePointNotFoundException(id));
        WasteCategory cat = categories.findById(wp.getCategoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL, "Missing category"));
        return toResponse(wp, cat.getCode());
    }

    @Transactional(readOnly = true)
    public MapResponse map(double minLon, double minLat, double maxLon, double maxLat, Integer limit) {
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<WastePoint> rows = wastePoints.findInBoundingBox(minLon, minLat, maxLon, maxLat, effectiveLimit);
        List<WastePointResponse> items = rows.stream()
                .map(wp -> toResponse(wp, categoryCodeOf(wp.getCategoryId())))
                .toList();
        return new MapResponse(items.size(), items);
    }

    private String categoryCodeOf(Short id) {
        return categories.findById(id).map(WasteCategory::getCode).orElse("UNKNOWN");
    }

    @Override
    @Transactional
    public ClaimedPin claimForUser(UUID pinId, UUID userId, OffsetDateTime expiresAt) {
        WastePoint wp = wastePoints.findById(pinId)
                .orElseThrow(() -> new WastePointNotFoundException(pinId));
        if (wp.getDeletedAt() != null) {
            throw new WastePointNotFoundException(pinId);
        }
        if (userId.equals(wp.getReportedByUserId())) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "Cannot claim a pin you reported");
        }
        if (!CLAIMABLE_STATUSES.contains(wp.getStatus())) {
            throw new BusinessException(ErrorCode.ALREADY_CLAIMED,
                    "Pin is not claimable (status=" + wp.getStatus() + ")");
        }
        wp.setStatus(WastePointStatus.CLAIMED);
        wp.setClaimedByUserId(userId);
        wp.setClaimedAt(OffsetDateTime.now());
        wp.setClaimExpiresAt(expiresAt);
        wastePoints.save(wp);
        return new ClaimedPin(wp.getId(), wp.getReportedByUserId());
    }

    @Override
    @Transactional(readOnly = true)
    public PinPointsContext getPointsContext(UUID pinId) {
        WastePoint wp = wastePoints.findById(pinId)
                .orElseThrow(() -> new WastePointNotFoundException(pinId));
        WasteCategory cat = categories.findById(wp.getCategoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL, "Missing category"));
        return new PinPointsContext(
                cat.getCode(),
                cat.getPointsMultiplier(),
                wp.getEstimatedVolume() == null ? null : wp.getEstimatedVolume().name(),
                wp.getReportedByUserId());
    }

    @Override
    @Transactional(readOnly = true)
    public PinLocation getLocation(UUID pinId) {
        WastePoint wp = wastePoints.findById(pinId)
                .orElseThrow(() -> new WastePointNotFoundException(pinId));
        Point loc = wp.getLocation();
        return new PinLocation(loc.getY(), loc.getX());
    }

    @Override
    @Transactional
    public void markVerified(UUID pinId, UUID collectionId) {
        WastePoint wp = wastePoints.findById(pinId)
                .orElseThrow(() -> new WastePointNotFoundException(pinId));
        wp.setStatus(WastePointStatus.VERIFIED);
        wp.setVerifiedCollectionId(collectionId);
        wastePoints.save(wp);
    }

    @Override
    @Transactional
    public void markRejected(UUID pinId) {
        WastePoint wp = wastePoints.findById(pinId)
                .orElseThrow(() -> new WastePointNotFoundException(pinId));
        wp.setStatus(WastePointStatus.ACTIVE);
        wp.setClaimedByUserId(null);
        wp.setClaimedAt(null);
        wp.setClaimExpiresAt(null);
        wastePoints.save(wp);
    }

    @Override
    @Transactional
    public void releaseClaim(UUID pinId, UUID userId) {
        WastePoint wp = wastePoints.findById(pinId)
                .orElseThrow(() -> new WastePointNotFoundException(pinId));
        if (wp.getStatus() != WastePointStatus.CLAIMED) {
            return;
        }
        if (!userId.equals(wp.getClaimedByUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not your claim");
        }
        wp.setStatus(WastePointStatus.ACTIVE);
        wp.setClaimedByUserId(null);
        wp.setClaimedAt(null);
        wp.setClaimExpiresAt(null);
        wastePoints.save(wp);
    }

    private WastePointResponse toResponse(WastePoint wp, String categoryCode) {
        Point loc = wp.getLocation();
        return new WastePointResponse(
                wp.getId(),
                categoryCode,
                loc.getY(),
                loc.getX(),
                wp.getAddressText(),
                wp.getEstimatedVolume(),
                wp.getDescription(),
                wp.getStatus(),
                wp.getReportedByUserId(),
                wp.getClaimedByUserId(),
                wp.getCreatedAt());
    }
}
