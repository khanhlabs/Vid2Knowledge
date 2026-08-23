package com.vid2knowledge.analysis.controller;

import com.vid2knowledge.analysis.application.AnalysisPreviewService;
import com.vid2knowledge.analysis.domain.LearningPackage;
import com.vid2knowledge.analysis.dto.AnalysisPreviewRequest;
import com.vid2knowledge.analysis.dto.AnalysisPreviewResponse;
import com.vid2knowledge.analysis.domain.NormalizedYoutubeUrl;
import com.vid2knowledge.analysis.application.YoutubeUrlParser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/api/v1/analysis")
public class AnalysisPreviewController {

    private final YoutubeUrlParser youtubeUrlParser;
    private final AnalysisPreviewService analysisPreviewService;

    public AnalysisPreviewController(
            YoutubeUrlParser youtubeUrlParser,
            AnalysisPreviewService analysisPreviewService
            ) {
        this.youtubeUrlParser = youtubeUrlParser;
        this.analysisPreviewService = analysisPreviewService;
    }

    @PostMapping("/preview")
    public ResponseEntity<LearningPackage> preview(
            @Valid @RequestBody AnalysisPreviewRequest request
    ) {
        NormalizedYoutubeUrl normalizedYoutubeUrl = youtubeUrlParser.parse(request.youtubeUrl());

        return ResponseEntity.ok(
                analysisPreviewService.generate(normalizedYoutubeUrl.canonicalUrl())
        );
    }

}
