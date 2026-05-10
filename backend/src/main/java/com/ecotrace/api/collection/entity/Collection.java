package com.ecotrace.api.collection.entity;

import com.ecotrace.api.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "collections")
public class Collection extends BaseEntity {

    @Column(name = "waste_point_id", nullable = false)
    private UUID wastePointId;

    @Column(name = "collector_user_id", nullable = false)
    private UUID collectorUserId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "collection_status")
    private CollectionStatus status;

    @Column(name = "claimed_at", nullable = false)
    private OffsetDateTime claimedAt;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "rejected_at")
    private OffsetDateTime rejectedAt;

    @Column(name = "reversed_at")
    private OffsetDateTime reversedAt;

    @Column(name = "reversed_by_user_id")
    private UUID reversedByUserId;

    @Column(name = "reversal_reason")
    private String reversalReason;

    @Column(name = "collector_location", columnDefinition = "geography(Point,4326)")
    private Point collectorLocation;

    @Column(name = "distance_from_pin_m", precision = 8, scale = 2)
    private BigDecimal distanceFromPinM;

    @Column(name = "dwell_seconds")
    private Integer dwellSeconds;

    @Column(name = "notes")
    private String notes;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "points_awarded", nullable = false)
    private int pointsAwarded;

    @Column(name = "reviewed_by_user_id")
    private UUID reviewedByUserId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void prePersist() {
        if (getId() == null) setId(UUID.randomUUID());
        if (status == null) status = CollectionStatus.CLAIMED;
        if (claimedAt == null) claimedAt = OffsetDateTime.now();
    }

    public UUID getWastePointId() { return wastePointId; }
    public void setWastePointId(UUID wastePointId) { this.wastePointId = wastePointId; }

    public UUID getCollectorUserId() { return collectorUserId; }
    public void setCollectorUserId(UUID collectorUserId) { this.collectorUserId = collectorUserId; }

    public CollectionStatus getStatus() { return status; }
    public void setStatus(CollectionStatus status) { this.status = status; }

    public OffsetDateTime getClaimedAt() { return claimedAt; }
    public void setClaimedAt(OffsetDateTime claimedAt) { this.claimedAt = claimedAt; }

    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(OffsetDateTime submittedAt) { this.submittedAt = submittedAt; }

    public OffsetDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(OffsetDateTime verifiedAt) { this.verifiedAt = verifiedAt; }

    public OffsetDateTime getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(OffsetDateTime rejectedAt) { this.rejectedAt = rejectedAt; }

    public OffsetDateTime getReversedAt() { return reversedAt; }
    public void setReversedAt(OffsetDateTime reversedAt) { this.reversedAt = reversedAt; }

    public UUID getReversedByUserId() { return reversedByUserId; }
    public void setReversedByUserId(UUID reversedByUserId) { this.reversedByUserId = reversedByUserId; }

    public String getReversalReason() { return reversalReason; }
    public void setReversalReason(String reversalReason) { this.reversalReason = reversalReason; }

    public Point getCollectorLocation() { return collectorLocation; }
    public void setCollectorLocation(Point collectorLocation) { this.collectorLocation = collectorLocation; }

    public BigDecimal getDistanceFromPinM() { return distanceFromPinM; }
    public void setDistanceFromPinM(BigDecimal distanceFromPinM) { this.distanceFromPinM = distanceFromPinM; }

    public Integer getDwellSeconds() { return dwellSeconds; }
    public void setDwellSeconds(Integer dwellSeconds) { this.dwellSeconds = dwellSeconds; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public int getPointsAwarded() { return pointsAwarded; }
    public void setPointsAwarded(int pointsAwarded) { this.pointsAwarded = pointsAwarded; }

    public UUID getReviewedByUserId() { return reviewedByUserId; }
    public void setReviewedByUserId(UUID reviewedByUserId) { this.reviewedByUserId = reviewedByUserId; }

    public long getVersion() { return version; }
}
