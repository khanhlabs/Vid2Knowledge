package com.vid2knowledge.analytics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/analytics/acquisition")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InternalAcquisitionController {
    private final AcquisitionAnalyticsService analytics;

    public InternalAcquisitionController(AcquisitionAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping
    public ResponseEntity<List<AcquisitionAnalyticsService.AcquisitionResult>> report() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(analytics.report());
    }
}
