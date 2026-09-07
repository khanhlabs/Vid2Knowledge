package com.vid2knowledge.analysis.application.port;

import com.vid2knowledge.analysis.domain.AnalysisSource;

/**
 * Outbound port for providers capable of understanding a video source.
 * Application services depend on this contract, never on a provider SDK.
 */
public interface VideoAnalysisProvider {

    AiGenerationResult generateLearningPackage(String prompt, AnalysisSource source);

    default AiGenerationResult generateLearningPackage(String prompt, String canonicalYoutubeUrl) {
        return generateLearningPackage(prompt, AnalysisSource.youtube(canonicalYoutubeUrl));
    }
}
