package com.ecotrace.api.collection.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectCollectionRequest(
        @NotBlank
        @Size(max = 500)
        String reason) {}
