package com.ecotrace.api.collection.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SubmitCollectionRequest(
        @NotEmpty
        @Size(min = 1, max = 6)
        List<EvidencePhoto> photos,
        @Size(max = 1000)
        String notes,
        @DecimalMin("-90.0") @DecimalMax("90.0")
        Double collectorLatitude,
        @DecimalMin("-180.0") @DecimalMax("180.0")
        Double collectorLongitude,
        Integer dwellSeconds) {

    public record EvidencePhoto(
            @jakarta.validation.constraints.NotBlank String storageKey,
            String kind) {}
}
