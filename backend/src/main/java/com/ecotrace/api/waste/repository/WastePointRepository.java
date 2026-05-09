package com.ecotrace.api.waste.repository;

import com.ecotrace.api.waste.entity.WastePoint;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WastePointRepository extends JpaRepository<WastePoint, UUID> {

    @Query(value = """
            SELECT * FROM waste_points
            WHERE deleted_at IS NULL
              AND status IN ('ACTIVE','CLAIMED','PENDING_REVIEW')
              AND ST_Within(
                    location::geometry,
                    ST_MakeEnvelope(:minLon, :minLat, :maxLon, :maxLat, 4326))
            LIMIT :limit
            """, nativeQuery = true)
    List<WastePoint> findInBoundingBox(
            @Param("minLon") double minLon,
            @Param("minLat") double minLat,
            @Param("maxLon") double maxLon,
            @Param("maxLat") double maxLat,
            @Param("limit") int limit);
}
