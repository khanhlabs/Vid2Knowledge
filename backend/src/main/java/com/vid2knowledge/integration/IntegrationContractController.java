package com.vid2knowledge.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@ConditionalOnProperty(prefix = "integrations", name = "enabled", havingValue = "true")
public class IntegrationContractController {
    private static final Resource CONTRACT = new ClassPathResource("integration-openapi.json");

    @GetMapping(value = "/api/v1/integrations/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> contract() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(CONTRACT);
    }
}
