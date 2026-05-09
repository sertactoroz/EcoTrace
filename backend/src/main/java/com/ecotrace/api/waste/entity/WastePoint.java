package com.ecotrace.api.waste.entity;

import com.ecotrace.api.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "waste_points")
public class WastePoint extends BaseEntity {

    @Column(name = "reported_by_user_id", nullable = false)
    private UUID reportedByUserId;

    @Column(name = "category_id", nullable = false)
    private Short categoryId;

    @Column(name = "location", nullable = false,
            columnDefinition = "geography(Point,4326)")
    private Point location;

    @Column(name = "location_geohash", nullable = false, length = 12)
    private String locationGeohash;

    @Column(name = "address_text")
    private String addressText;

    @Column(name = "region_code", length = 16)
    private String regionCode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estimated_volume", nullable = false,
            columnDefinition = "waste_volume")
    private WasteVolume estimatedVolume;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false,
            columnDefinition = "waste_point_status")
    private WastePointStatus status;

    @Column(name = "claimed_by_user_id")
    private UUID claimedByUserId;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @Column(name = "claim_expires_at")
    private OffsetDateTime claimExpiresAt;

    @Column(name = "verified_collection_id")
    private UUID verifiedCollectionId;

    @Column(name = "reports_count", nullable = false)
    private int reportsCount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @PrePersist
    void prePersist() {
        if (getId() == null) setId(UUID.randomUUID());
        if (status == null) status = WastePointStatus.PENDING_REVIEW;
        if (estimatedVolume == null) estimatedVolume = WasteVolume.SMALL;
    }

    public UUID getReportedByUserId() { return reportedByUserId; }
    public void setReportedByUserId(UUID reportedByUserId) { this.reportedByUserId = reportedByUserId; }

    public Short getCategoryId() { return categoryId; }
    public void setCategoryId(Short categoryId) { this.categoryId = categoryId; }

    public Point getLocation() { return location; }
    public void setLocation(Point location) { this.location = location; }

    public String getLocationGeohash() { return locationGeohash; }
    public void setLocationGeohash(String locationGeohash) { this.locationGeohash = locationGeohash; }

    public String getAddressText() { return addressText; }
    public void setAddressText(String addressText) { this.addressText = addressText; }

    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String regionCode) { this.regionCode = regionCode; }

    public WasteVolume getEstimatedVolume() { return estimatedVolume; }
    public void setEstimatedVolume(WasteVolume estimatedVolume) { this.estimatedVolume = estimatedVolume; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public WastePointStatus getStatus() { return status; }
    public void setStatus(WastePointStatus status) { this.status = status; }

    public UUID getClaimedByUserId() { return claimedByUserId; }
    public void setClaimedByUserId(UUID claimedByUserId) { this.claimedByUserId = claimedByUserId; }

    public OffsetDateTime getClaimedAt() { return claimedAt; }
    public void setClaimedAt(OffsetDateTime claimedAt) { this.claimedAt = claimedAt; }

    public OffsetDateTime getClaimExpiresAt() { return claimExpiresAt; }
    public void setClaimExpiresAt(OffsetDateTime claimExpiresAt) { this.claimExpiresAt = claimExpiresAt; }

    public UUID getVerifiedCollectionId() { return verifiedCollectionId; }
    public void setVerifiedCollectionId(UUID verifiedCollectionId) { this.verifiedCollectionId = verifiedCollectionId; }

    public int getReportsCount() { return reportsCount; }
    public void setReportsCount(int reportsCount) { this.reportsCount = reportsCount; }

    public long getVersion() { return version; }

    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
}
