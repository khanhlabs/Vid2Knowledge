package com.vid2knowledge.analysis.application;

import com.vid2knowledge.common.exception.InvalidYoutubeUrlException;
import com.vid2knowledge.analysis.domain.NormalizedYoutubeUrl;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class YoutubeUrlParser {

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-_]{11}$");

    public NormalizedYoutubeUrl parse(String rawUrl){
        if (rawUrl == null || rawUrl.isBlank()){
            throw new InvalidYoutubeUrlException("YouTube URL is required");
        }

        try {
            URI uri = URI.create(rawUrl.trim());

            if (!isSupportedHttpsUrl(uri) || uri.getUserInfo() != null) {
                throw invalid();
            }

            String videoId = extractVideoId(uri)
                    .filter(this::isValidVideoId)
                    .orElseThrow(this::invalid);

            return new NormalizedYoutubeUrl(
                    videoId,
                    "https://www.youtube.com/watch?v=" + videoId
            );
        }catch (IllegalArgumentException e){
            throw invalid();
        }
    }

    private Optional<String> extractVideoId(URI uri){
        String host = uri.getHost().toLowerCase();
        String path = uri.getPath();

        if (host.equals("youtu.be") || host.equals("www.youtu.be")){
            return Arrays.stream(path.split("/"))
                    .filter(segment -> !segment.isBlank())
                    .findFirst();
        }

        if (!host.equals("youtube.com") && !host.equals("www.youtube.com") && !host.equals("m.youtube.com")){
            return Optional.empty();
        }

        if ("/watch".equals(path)){
            return queryParameter(uri, "v");
        }

        if (path.startsWith("/shorts/")) {
            String[] parts = path.split("/");

            if (parts.length >= 3 && !parts[2].isBlank()) {
                return Optional.of(parts[2]);
            }

            return Optional.empty();
        }

        return Optional.empty();

    }

    private Optional<String> queryParameter(URI uri, String expectedName){
        String rawQuery = uri.getQuery();
        if(rawQuery == null || rawQuery.isBlank()){
            return Optional.empty();
        }

        return Arrays.stream(rawQuery.split("&"))
                .map(parameter -> parameter.split("=", 2))
                .filter(parts -> parts.length == 2 && parts[0].equals(expectedName))
                .map(parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
                .findFirst();
    }

    private boolean isSupportedHttpsUrl(URI uri){
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private boolean isValidVideoId(String videoId){
        return VIDEO_ID_PATTERN.matcher(videoId).matches();
    }

    private InvalidYoutubeUrlException invalid(){
        return new InvalidYoutubeUrlException("Unsupported or invalid YouTube URL");
    }
}
