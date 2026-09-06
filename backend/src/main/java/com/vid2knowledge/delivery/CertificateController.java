package com.vid2knowledge.delivery;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/certificates")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class CertificateController {

    private final LearningPathService learningPaths;

    public CertificateController(LearningPathService learningPaths) {
        this.learningPaths = learningPaths;
    }

    @GetMapping("/{verificationCode}")
    public LearningPathService.VerifiedCertificate verify(
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9]{20}$") String verificationCode
    ) {
        return learningPaths.verify(verificationCode);
    }
}
