package com.ecotrace.api.waste.dto.response;

import com.ecotrace.api.waste.entity.WastePointStatus;
import com.ecotrace.api.waste.entity.WasteVolume;
import java.time.OffsetDateTime;
import java.util.UUID;

public record WastePointResponse(
        UUID id,
        String categoryCode,
        double latitude,
        double longitude,
        String addressText,
        WasteVolume estimatedVolume,
        String description,
        WastePointStatus status,
        UUID reportedByUserId,
        UUID claimedByUserId,
        OffsetDateTime createdAt) {}
