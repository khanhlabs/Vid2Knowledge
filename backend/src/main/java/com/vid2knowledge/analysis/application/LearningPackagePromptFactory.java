package com.vid2knowledge.analysis.application;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class LearningPackagePromptFactory {

    private final ObjectMapper mapper;

    public LearningPackagePromptFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String create() {
        return create("{}");
    }

    public String create(String outputProfileJson) {
        AnalysisOutputProfile profile = AnalysisOutputProfile.parse(outputProfileJson, mapper);
        return """
                Analyze the provided video and create a learning package.

                Return ONLY one valid JSON object.
                Do not use Markdown.
                Do not wrap the JSON in code fences.
                Do not add any text before or after the JSON.

                The JSON must use exactly this structure:
                {
                    "schemaVersion": "learning-package-v3",
                  "video": {
                    "youtubeUrl": "string or null",
                    "videoId": "11-character YouTube ID or null",
                    "sourceType": "YOUTUBE or UPLOAD",
                    "sourceId": "stable source identifier",
                    "title": "string",
                    "language": "string"
                  },
                  "summary": {
                    "overview": "string",
                    "sections": [
                      {
                        "id": "section-01",
                        "title": "string",
                        "source": {"timestampSeconds": 0, "evidence": "short supporting quote or close paraphrase"},
                        "content": ["string"]
                      }
                    ]
                  },
                  "keyTakeaways": [
                    {
                      "id": "takeaway-01",
                      "text": "string",
                      "source": {"timestampSeconds": 0, "evidence": "short supporting quote or close paraphrase"}
                    }
                  ],
                  "flashcards": [
                    {
                      "id": "card-01",
                      "question": "string",
                      "answer": "string",
                      "source": {"timestampSeconds": 0, "evidence": "short supporting quote or close paraphrase"}
                    }
                  ],
                  "quiz": [
                    {
                      "id": "quiz-01",
                      "question": "string",
                      "options": ["string", "string", "string", "string"],
                      "correctAnswerIndex": 0,
                      "explanation": "string",
                      "source": {"timestampSeconds": 0, "evidence": "short supporting quote or close paraphrase"}
                    }
                  ]
                }

                Requirements:
                - Include at least one summary section.
                - Include at least one key takeaway.
                - Every quiz question must have exactly 4 options.
                - correctAnswerIndex must be an integer from 0 to 3.
                - Every section, takeaway, flashcard, and quiz question must have a source object.
                - timestampSeconds is an integer at or after zero and must be within the video duration.
                - evidence must be a short quote or close paraphrase that directly supports the item.
                - IDs must be unique lowercase slugs and stable within this output.
                - Questions and the four options within a question must not be duplicates.
                - Do not invent facts, timestamps, or details that are not supported by the video.
                - Do not guess source identifiers or URLs; the server assigns all video source identity fields.

                """ + profile.promptInstructions();
    }
}
