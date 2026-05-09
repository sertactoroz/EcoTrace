package com.ecotrace.api.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecotrace.api.collection.event.CollectionRejected;
import com.ecotrace.api.collection.event.CollectionVerified;
import com.ecotrace.api.gamification.event.PointsAwarded;
import com.ecotrace.api.notification.api.UserEvent;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserStreamFanoutTest {

    private final RecordingStream stream = new RecordingStream();
    private final UserStreamFanout fanout = new UserStreamFanout(stream);

    @Test
    void points_awarded_emits_points_awarded_event_to_collector() {
        UUID userId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        UUID pinId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        fanout.on(new PointsAwarded(txId, userId, collectionId, pinId,
                25, 250L, 3, "COLLECTION", now));

        assertThat(stream.published).hasSize(1);
        var rec = stream.published.get(0);
        assertThat(rec.userId).isEqualTo(userId);
        assertThat(rec.event.type()).isEqualTo("points.awarded");
        assertThat(rec.event.data()).containsEntry("delta", 25)
                .containsEntry("totalPoints", 250L)
                .containsEntry("level", 3)
                .containsEntry("reason", "COLLECTION")
                .containsEntry("transactionId", txId)
                .containsEntry("collectionId", collectionId);
    }

    @Test
    void collection_verified_targets_collector_not_moderator() {
        UUID collector = UUID.randomUUID();
        UUID moderator = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        UUID pinId = UUID.randomUUID();

        fanout.on(new CollectionVerified(collectionId, pinId, collector, moderator,
                30, OffsetDateTime.now()));

        assertThat(stream.published).hasSize(1);
        var rec = stream.published.get(0);
        assertThat(rec.userId).isEqualTo(collector);
        assertThat(rec.event.type()).isEqualTo("collection.verified");
        assertThat(rec.event.data()).containsEntry("pointsAwarded", 30)
                .containsEntry("collectionId", collectionId)
                .containsEntry("wastePointId", pinId);
    }

    @Test
    void collection_rejected_includes_reason() {
        UUID collector = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        UUID pinId = UUID.randomUUID();

        fanout.on(new CollectionRejected(collectionId, pinId, collector, UUID.randomUUID(),
                "blurry photo", OffsetDateTime.now()));

        assertThat(stream.published).hasSize(1);
        var rec = stream.published.get(0);
        assertThat(rec.userId).isEqualTo(collector);
        assertThat(rec.event.type()).isEqualTo("collection.rejected");
        assertThat(rec.event.data()).containsEntry("reason", "blurry photo");
    }

    private static final class RecordingStream extends UserEventStream {
        record Record(UUID userId, UserEvent event) {}
        final List<Record> published = new ArrayList<>();

        @Override
        public void publish(UUID userId, UserEvent event) {
            published.add(new Record(userId, event));
        }
    }
}
