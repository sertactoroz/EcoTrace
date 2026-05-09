package com.ecotrace.api.waste.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "waste_categories")
public class WasteCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Short id;

    @Column(name = "code", nullable = false, length = 32, unique = true)
    private String code;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Column(name = "icon_url")
    private String iconUrl;

    @Column(name = "points_multiplier", nullable = false, precision = 4, scale = 2)
    private BigDecimal pointsMultiplier;

    @Column(name = "is_hazardous", nullable = false)
    private boolean hazardous;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Short getId() { return id; }
    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public String getIconUrl() { return iconUrl; }
    public BigDecimal getPointsMultiplier() { return pointsMultiplier; }
    public boolean isHazardous() { return hazardous; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
