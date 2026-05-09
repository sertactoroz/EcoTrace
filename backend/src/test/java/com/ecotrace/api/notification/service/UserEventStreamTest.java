package com.ecotrace.api.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecotrace.api.notification.api.UserEvent;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserEventStreamTest {

    private final UserEventStream stream = new UserEventStream();

    @Test
    void open_registers_emitter_and_sends_ready() {
        UUID userId = UUID.randomUUID();
        var e = stream.open(userId);

        assertThat(e).isNotNull();
        assertThat(stream.activeStreams(userId)).isEqualTo(1);
    }

    @Test
    void multiple_emitters_per_user_are_kept() {
        UUID userId = UUID.randomUUID();
        stream.open(userId);
        stream.open(userId);

        assertThat(stream.activeStreams(userId)).isEqualTo(2);
    }

    @Test
    void publish_to_unknown_user_is_noop() {
        // No emitter registered → must not throw
        stream.publish(UUID.randomUUID(),
                new UserEvent("anything", Map.of("k", "v"), OffsetDateTime.now()));
    }
}
