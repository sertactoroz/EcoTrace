package com.ecotrace.api.waste.dto.response;

import java.util.List;

public record MapResponse(int count, List<WastePointResponse> items) {}
