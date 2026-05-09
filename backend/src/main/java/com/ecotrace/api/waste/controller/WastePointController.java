package com.ecotrace.api.waste.controller;

import com.ecotrace.api.security.annotation.CurrentUser;
import com.ecotrace.api.security.principal.AuthenticatedUser;
import com.ecotrace.api.waste.dto.request.CreateWastePointRequest;
import com.ecotrace.api.waste.dto.response.WastePointResponse;
import com.ecotrace.api.waste.service.WastePointService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/waste-points")
public class WastePointController {

    private final WastePointService service;

    public WastePointController(WastePointService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WastePointResponse> create(
            @CurrentUser AuthenticatedUser user,
            @Valid @RequestBody CreateWastePointRequest req) {
        WastePointResponse created = service.create(user.userId(), req);
        return ResponseEntity.created(URI.create("/waste-points/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WastePointResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(service.get(id));
    }
}
