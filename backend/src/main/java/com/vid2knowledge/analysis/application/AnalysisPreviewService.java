package com.vid2knowledge.analysis.application;

import com.vid2knowledge.analysis.domain.LearningPackage;
import com.vid2knowledge.analysis.application.port.VideoAnalysisProvider;
import com.vid2knowledge.analysis.application.port.AiGenerationResult;
import org.springframework.stereotype.Service;

@Service
public class AnalysisPreviewService {

    private final VideoAnalysisProvider videoAnalysisProvider;
    private final LearningPackagePromptFactory promptFactory;
    private final LearningPackageCodec codec;

    public AnalysisPreviewService(
            VideoAnalysisProvider videoAnalysisProvider,
            LearningPackagePromptFactory promptFactory,
            LearningPackageCodec codec
    ){
        this.videoAnalysisProvider = videoAnalysisProvider;
        this.promptFactory = promptFactory;
        this.codec = codec;
    }

    public LearningPackage generate(String canonicalYoutubeUrl){
        AiGenerationResult generation = videoAnalysisProvider.generateLearningPackage(
                promptFactory.create(),
                canonicalYoutubeUrl
        );

        return codec.parseAndValidate(generation.output(), canonicalYoutubeUrl);
    }

}

