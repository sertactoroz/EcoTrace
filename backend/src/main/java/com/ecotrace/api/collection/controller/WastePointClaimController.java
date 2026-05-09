package com.ecotrace.api.collection.controller;

import com.ecotrace.api.collection.dto.response.CollectionResponse;
import com.ecotrace.api.collection.service.CollectionService;
import com.ecotrace.api.security.annotation.CurrentUser;
import com.ecotrace.api.security.principal.AuthenticatedUser;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/waste-points")
public class WastePointClaimController {

    private final CollectionService service;

    public WastePointClaimController(CollectionService service) {
        this.service = service;
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<CollectionResponse> claim(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID id) {
        CollectionResponse created = service.claim(user.userId(), id);
        return ResponseEntity.created(URI.create("/collections/" + created.id())).body(created);
    }
}
