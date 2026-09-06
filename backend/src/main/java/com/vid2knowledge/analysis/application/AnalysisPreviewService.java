package com.vid2knowledge.analysis.application;

import com.vid2knowledge.analysis.domain.LearningPackage;
import com.vid2knowledge.analysis.application.port.VideoAnalysisProvider;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.boot.json.JsonParseException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

@Service
public class AnalysisPreviewService {

    private final VideoAnalysisProvider videoAnalysisProvider;
    private final LearningPackagePromptFactory promptFactory;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public AnalysisPreviewService(
            VideoAnalysisProvider videoAnalysisProvider,
            LearningPackagePromptFactory promptFactory,
            ObjectMapper objectMapper,
            Validator validator
    ){
        this.videoAnalysisProvider = videoAnalysisProvider;
        this.promptFactory = promptFactory;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public LearningPackage generate(String canonicalYoutubeUrl){
        String rawOutput = videoAnalysisProvider.generateLearningPackage(
                promptFactory.create(),
                canonicalYoutubeUrl
        );

        LearningPackage learningPackage = parse(rawOutput);
        validate(learningPackage);

        return new LearningPackage(
                new LearningPackage.Video(
                        canonicalYoutubeUrl,
                        learningPackage.video().title(),
                        learningPackage.video().language()
                ),
                learningPackage.summary(),
                learningPackage.keyTakeaways(),
                learningPackage.flashcards(),
                learningPackage.quiz()
        );
    }

    private LearningPackage parse(String rawOutput){
        try {
            return objectMapper.readValue(
                    removeCodeFenceIfPresent(rawOutput),
                    LearningPackage.class
            );
        }catch (JsonParseException exception){
            throw new IllegalStateException(
                    "Gemini returned invalid Json for the LearningPackage",
                    exception
            );
        }
    }

    private void validate(LearningPackage learningPackage){
        Set<ConstraintViolation<LearningPackage>> violations = validator.validate(learningPackage);

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Gemini returned an invalid LearningPackage"
            );
        }
    }

    private String removeCodeFenceIfPresent(String rawOutput){
        String text = rawOutput.trim();

        if (!text.startsWith("```")){
            return text;
        }

        int firstLineBreak = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");

        if (firstLineBreak < 0 || lastFence <= firstLineBreak){
            return text;
        }

        return text.substring(firstLineBreak + 1, lastFence).trim();
    }

}

