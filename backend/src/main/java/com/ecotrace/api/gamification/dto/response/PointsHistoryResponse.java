package com.ecotrace.api.gamification.dto.response;

import java.util.List;

public record PointsHistoryResponse(
        List<PointsTransactionResponse> items,
        String nextCursor) {}
