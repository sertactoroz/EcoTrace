package com.ecotrace.api.identity.dto.request;

import jakarta.validation.constraints.NotBlank;

public record GoogleAuthRequest(@NotBlank String idToken, String deviceId) {}
