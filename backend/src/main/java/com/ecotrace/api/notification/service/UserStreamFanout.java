package com.ecotrace.api.notification.service;

import com.ecotrace.api.collection.event.CollectionRejected;
import com.ecotrace.api.collection.event.CollectionVerified;
import com.ecotrace.api.gamification.event.PointsAwarded;
import com.ecotrace.api.notification.api.UserEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class UserStreamFanout {

    private final UserEventStream stream;

    UserStreamFanout(UserEventStream stream) {
        this.stream = stream;
    }

    @EventListener
    public void on(PointsAwarded event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transactionId", event.transactionId());
        data.put("collectionId", event.collectionId());
        data.put("delta", event.delta());
        data.put("totalPoints", event.newTotalPoints());
        data.put("level", event.newLevel());
        data.put("reason", event.reason());
        stream.publish(event.userId(), new UserEvent("points.awarded", data, event.occurredAt()));
    }

    @EventListener
    public void on(CollectionVerified event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("collectionId", event.collectionId());
        data.put("wastePointId", event.wastePointId());
        data.put("pointsAwarded", event.pointsAwarded());
        stream.publish(event.collectorUserId(),
                new UserEvent("collection.verified", data, event.verifiedAt()));
    }

    @EventListener
    public void on(CollectionRejected event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("collectionId", event.collectionId());
        data.put("wastePointId", event.wastePointId());
        data.put("reason", event.reason());
        stream.publish(event.collectorUserId(),
                new UserEvent("collection.rejected", data, event.rejectedAt()));
    }
}
