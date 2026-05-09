package com.ecotrace.api.notification.controller;

import com.ecotrace.api.notification.service.UserEventStream;
import com.ecotrace.api.security.annotation.CurrentUser;
import com.ecotrace.api.security.principal.AuthenticatedUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/me")
public class UserStreamController {

    private final UserEventStream stream;

    public UserStreamController(UserEventStream stream) {
        this.stream = stream;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@CurrentUser AuthenticatedUser user) {
        return stream.open(user.userId());
    }
}
