package com.ecotrace.api.gamification.service;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.gamification.dto.response.PointsHistoryResponse;
import com.ecotrace.api.gamification.dto.response.PointsTransactionResponse;
import com.ecotrace.api.gamification.entity.PointsTransaction;
import com.ecotrace.api.gamification.repository.PointsTransactionRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointsHistoryService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final PointsTransactionRepository transactions;

    public PointsHistoryService(PointsTransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public PointsHistoryResponse history(UUID userId, Integer rawLimit, String cursor) {
        int limit = clamp(rawLimit);
        Limit fetch = Limit.of(limit + 1);
        List<PointsTransaction> rows = (cursor == null || cursor.isBlank())
                ? transactions.findPageByUserId(userId, fetch)
                : decodeAndFetch(userId, cursor, fetch);

        boolean hasMore = rows.size() > limit;
        List<PointsTransaction> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? encode(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
                : null;

        List<PointsTransactionResponse> items = page.stream()
                .map(PointsHistoryService::toResponse)
                .toList();
        return new PointsHistoryResponse(items, nextCursor);
    }

    private List<PointsTransaction> decodeAndFetch(UUID userId, String cursor, Limit fetch) {
        Cursor c = decode(cursor);
        return transactions.findPageByUserIdBefore(userId, c.ts(), c.id(), fetch);
    }

    private static int clamp(Integer rawLimit) {
        if (rawLimit == null) return DEFAULT_LIMIT;
        if (rawLimit < 1) return 1;
        return Math.min(rawLimit, MAX_LIMIT);
    }

    private static String encode(OffsetDateTime ts, UUID id) {
        String raw = ts.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            if (sep <= 0) throw new IllegalArgumentException("missing separator");
            OffsetDateTime ts = OffsetDateTime.parse(raw.substring(0, sep));
            UUID id = UUID.fromString(raw.substring(sep + 1));
            return new Cursor(ts, id);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Invalid cursor");
        }
    }

    private static PointsTransactionResponse toResponse(PointsTransaction t) {
        return new PointsTransactionResponse(
                t.getId(),
                t.getDelta(),
                t.getReason().name(),
                t.getCollectionId(),
                t.getWastePointId(),
                t.getCreatedAt());
    }

    private record Cursor(OffsetDateTime ts, UUID id) {}
}
