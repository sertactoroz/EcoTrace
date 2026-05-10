package com.ecotrace.api.collection.controller;

import com.ecotrace.api.collection.dto.request.RejectCollectionRequest;
import com.ecotrace.api.collection.dto.request.ReverseCollectionRequest;
import com.ecotrace.api.collection.dto.request.SubmitCollectionRequest;
import com.ecotrace.api.collection.dto.response.CollectionResponse;
import com.ecotrace.api.collection.service.CollectionService;
import com.ecotrace.api.security.annotation.CurrentUser;
import com.ecotrace.api.security.principal.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/collections")
public class CollectionController {

    private final CollectionService service;

    public CollectionController(CollectionService service) {
        this.service = service;
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<CollectionResponse> submit(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody SubmitCollectionRequest req) {
        return ResponseEntity.ok(service.submit(user.userId(), id, req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CollectionResponse> get(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID id) {
        return ResponseEntity.ok(service.get(user.userId(), id));
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasRole('MODERATOR')")
    public ResponseEntity<CollectionResponse> verify(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID id) {
        return ResponseEntity.ok(service.verify(user.userId(), id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('MODERATOR')")
    public ResponseEntity<CollectionResponse> reject(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody RejectCollectionRequest req) {
        return ResponseEntity.ok(service.reject(user.userId(), id, req));
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CollectionResponse> reverse(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody ReverseCollectionRequest req) {
        return ResponseEntity.ok(service.reverse(user.userId(), id, req));
    }
}
