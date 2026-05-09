package com.ecotrace.api.media.service;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.config.properties.StorageProperties;
import com.ecotrace.api.media.api.MediaUrlResolver;
import com.ecotrace.api.media.dto.request.PresignUploadRequest;
import com.ecotrace.api.media.dto.response.PresignedUploadResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class MediaService implements MediaUrlResolver {

    private final S3Presigner presigner;
    private final S3Client s3;
    private final StorageProperties props;

    public MediaService(S3Presigner presigner, S3Client s3, StorageProperties props) {
        this.presigner = presigner;
        this.s3 = s3;
        this.props = props;
    }

    public PresignedUploadResponse presignUpload(UUID userId, PresignUploadRequest req) {
        if (req.sizeBytes() > props.maxUploadBytes()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "File too large (max " + props.maxUploadBytes() + " bytes)");
        }
        String purpose = req.purpose() == null || req.purpose().isBlank() ? "general" : req.purpose();
        String ext = extensionFor(req.contentType());
        String storageKey = "uploads/%s/%s/%s.%s".formatted(purpose, userId, UUID.randomUUID(), ext);

        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(storageKey)
                .contentType(req.contentType())
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(props.presignTtl())
                        .putObjectRequest(put)
                        .build());

        Instant expires = Instant.now().plus(props.presignTtl());
        return new PresignedUploadResponse(
                storageKey,
                presigned.url().toString(),
                publicUrl(storageKey),
                "PUT",
                req.contentType(),
                OffsetDateTime.ofInstant(expires, ZoneOffset.UTC));
    }

    public boolean exists(String storageKey) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(storageKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public String publicUrl(String storageKey) {
        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            return stripTrailingSlash(props.endpoint()) + "/" + props.bucket() + "/" + storageKey;
        }
        return "https://" + props.bucket() + ".s3." + props.region() + ".amazonaws.com/" + storageKey;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/heic" -> "heic";
            default -> "bin";
        };
    }
}
