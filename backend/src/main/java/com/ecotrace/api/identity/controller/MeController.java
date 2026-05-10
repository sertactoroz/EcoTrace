package com.ecotrace.api.identity.controller;

import com.ecotrace.api.identity.dto.response.MeResponse;
import com.ecotrace.api.identity.service.MeService;
import com.ecotrace.api.security.annotation.CurrentUser;
import com.ecotrace.api.security.principal.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me")
public class MeController {

    private final MeService service;

    public MeController(MeService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<MeResponse> me(@CurrentUser AuthenticatedUser user) {
        return ResponseEntity.ok(service.load(user.userId()));
    }
}
