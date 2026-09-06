package com.vid2knowledge.analysis.application.port;

import java.util.List;

public interface KnowledgeAiProvider {

    List<List<Double>> embed(List<String> texts, EmbeddingPurpose purpose);

    AiGenerationResult generateGroundedAnswer(String prompt);

    String embeddingModel();

    enum EmbeddingPurpose { DOCUMENT, QUERY }
}
