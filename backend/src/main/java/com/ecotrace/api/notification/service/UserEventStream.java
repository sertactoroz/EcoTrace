package com.ecotrace.api.notification.service;

import com.ecotrace.api.notification.api.UserEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class UserEventStream {

    private static final Logger log = LoggerFactory.getLogger(UserEventStream.class);
    private static final long STREAM_TTL_MS = Duration.ofMinutes(30).toMillis();

    private final ConcurrentHashMap<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter open(UUID userId) {
        SseEmitter emitter = new SseEmitter(STREAM_TTL_MS);
        List<SseEmitter> bucket = emitters.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>());
        bucket.add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));

        try {
            emitter.send(SseEmitter.event().name("ready").data("ok"));
        } catch (IOException e) {
            remove(userId, emitter);
        }
        return emitter;
    }

    public void publish(UUID userId, UserEvent event) {
        List<SseEmitter> bucket = emitters.get(userId);
        if (bucket == null || bucket.isEmpty()) return;

        for (SseEmitter emitter : bucket) {
            try {
                emitter.send(SseEmitter.event().name(event.type()).data(event));
            } catch (IOException e) {
                remove(userId, emitter);
                emitter.completeWithError(e);
            } catch (IllegalStateException e) {
                remove(userId, emitter);
            }
        }
    }

    int activeStreams(UUID userId) {
        List<SseEmitter> bucket = emitters.get(userId);
        return bucket == null ? 0 : bucket.size();
    }

    private void remove(UUID userId, SseEmitter emitter) {
        List<SseEmitter> bucket = emitters.get(userId);
        if (bucket == null) return;
        bucket.remove(emitter);
        if (bucket.isEmpty()) {
            emitters.remove(userId, bucket);
        }
        log.debug("SSE emitter closed for user {} (remaining={})", userId, bucket.size());
    }
}
