package com.ecotrace.api.waste.controller;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import com.ecotrace.api.waste.dto.response.MapResponse;
import com.ecotrace.api.waste.service.WastePointService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/map")
public class MapController {

    private final WastePointService service;

    public MapController(WastePointService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<MapResponse> bbox(
            @RequestParam double minLon,
            @RequestParam double minLat,
            @RequestParam double maxLon,
            @RequestParam double maxLat,
            @RequestParam(required = false) Integer limit) {
        if (minLon >= maxLon || minLat >= maxLat) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Invalid bounding box");
        }
        return ResponseEntity.ok(service.map(minLon, minLat, maxLon, maxLat, limit));
    }
}
