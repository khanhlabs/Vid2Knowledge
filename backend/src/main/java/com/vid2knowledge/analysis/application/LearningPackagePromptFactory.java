package com.vid2knowledge.analysis.application;

import org.springframework.stereotype.Component;

@Component
public class LearningPackagePromptFactory {

    public String create() {
        return """
                Analyze the provided public YouTube video and create a learning package.

                Return ONLY one valid JSON object.
                Do not use Markdown.
                Do not wrap the JSON in code fences.
                Do not add any text before or after the JSON.

                Use the main language spoken in the video.

                The JSON must use exactly this structure:
                {
                  "video": {
                    "youtubeUrl": "string",
                    "title": "string",
                    "language": "string"
                  },
                  "summary": {
                    "overview": "string",
                    "sections": [
                      {
                        "title": "string",
                        "timestamp": "MM:SS or HH:MM:SS, optional",
                        "content": ["string"]
                      }
                    ]
                  },
                  "keyTakeaways": ["string"],
                  "flashcards": [
                    {
                      "question": "string",
                      "answer": "string",
                      "timestamp": "MM:SS or HH:MM:SS, optional"
                    }
                  ],
                  "quiz": [
                    {
                      "question": "string",
                      "options": ["string", "string", "string", "string"],
                      "correctAnswerIndex": 0,
                      "explanation": "string",
                      "timestamp": "MM:SS or HH:MM:SS, optional"
                    }
                  ]
                }

                Requirements:
                - Include at least one summary section.
                - Include at least one key takeaway.
                - Generate 10 to 20 flashcards.
                - Generate 5 to 10 quiz questions.
                - Every quiz question must have exactly 4 options.
                - correctAnswerIndex must be an integer from 0 to 3.
                - Use timestamps only when supported by the video content.
                - Do not invent facts, timestamps, or details that are not supported by the video.
                - video.youtubeUrl must be the exact canonical YouTube URL provided in the video input.
                - Never use placeholders such as VIDEO_ID.
                """;
    }
}