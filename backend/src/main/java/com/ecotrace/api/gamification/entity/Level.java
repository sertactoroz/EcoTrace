package com.ecotrace.api.gamification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "levels")
public class Level {

    @Id
    @Column(name = "level", nullable = false)
    private Integer level;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "min_points", nullable = false, unique = true)
    private Integer minPoints;

    @Column(name = "icon_url")
    private String iconUrl;

    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getMinPoints() { return minPoints; }
    public void setMinPoints(Integer minPoints) { this.minPoints = minPoints; }

    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
}
