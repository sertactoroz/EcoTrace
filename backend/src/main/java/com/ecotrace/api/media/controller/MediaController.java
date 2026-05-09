package com.ecotrace.api.media.controller;

import com.ecotrace.api.media.dto.request.PresignUploadRequest;
import com.ecotrace.api.media.dto.response.PresignedUploadResponse;
import com.ecotrace.api.media.service.MediaService;
import com.ecotrace.api.security.annotation.CurrentUser;
import com.ecotrace.api.security.principal.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/media")
public class MediaController {

    private final MediaService service;

    public MediaController(MediaService service) {
        this.service = service;
    }

    @PostMapping("/uploads")
    public ResponseEntity<PresignedUploadResponse> presign(
            @CurrentUser AuthenticatedUser user,
            @Valid @RequestBody PresignUploadRequest req) {
        return ResponseEntity.ok(service.presignUpload(user.userId(), req));
    }
}
