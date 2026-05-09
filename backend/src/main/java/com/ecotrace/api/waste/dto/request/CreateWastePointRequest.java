package com.ecotrace.api.waste.dto.request;

import com.ecotrace.api.waste.entity.WasteVolume;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateWastePointRequest(
        @NotBlank String categoryCode,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @NotNull WasteVolume estimatedVolume,
        String description,
        String addressText,
        String regionCode) {}
