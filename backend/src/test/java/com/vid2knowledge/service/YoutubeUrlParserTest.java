package com.vid2knowledge.service;

import com.vid2knowledge.analysis.application.YoutubeUrlParser;
import com.vid2knowledge.common.exception.InvalidYoutubeUrlException;
import com.vid2knowledge.analysis.domain.NormalizedYoutubeUrl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YoutubeUrlParserTest {

    private final YoutubeUrlParser parser = new YoutubeUrlParser();

    @Test
    void parsesYoutubeWatchUrl() {
        NormalizedYoutubeUrl result =
                parser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        assertEquals("dQw4w9WgXcQ", result.videoId());
        assertEquals(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                result.canonicalUrl()
        );
    }

    @Test
    void parsesShortYoutubeUrl() {
        NormalizedYoutubeUrl result =
                parser.parse("https://youtu.be/dQw4w9WgXcQ?t=20");

        assertEquals("dQw4w9WgXcQ", result.videoId());
    }

    @Test
    void parsesYoutubeShortsUrl() {
        NormalizedYoutubeUrl result =
                parser.parse("https://www.youtube.com/shorts/dQw4w9WgXcQ");

        assertEquals("dQw4w9WgXcQ", result.videoId());
    }

    @Test
    void rejectsNonYoutubeUrl() {
        assertThrows(
                InvalidYoutubeUrlException.class,
                () -> parser.parse("https://example.com/watch?v=dQw4w9WgXcQ")
        );
    }

    @Test
    void rejectsInvalidVideoId() {
        assertThrows(
                InvalidYoutubeUrlException.class,
                () -> parser.parse("https://www.youtube.com/watch?v=invalid")
        );
    }
}