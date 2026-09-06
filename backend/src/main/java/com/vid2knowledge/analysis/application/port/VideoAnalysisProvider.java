package com.vid2knowledge.analysis.application.port;

/**
 * Outbound port for providers capable of understanding a video source.
 * Application services depend on this contract, never on a provider SDK.
 */
public interface VideoAnalysisProvider {

    String generateLearningPackage(String prompt, String canonicalYoutubeUrl);
}
