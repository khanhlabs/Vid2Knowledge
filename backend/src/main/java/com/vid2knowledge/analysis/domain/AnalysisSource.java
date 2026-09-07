package com.vid2knowledge.analysis.domain;

import java.util.UUID;

public record AnalysisSource(
        UUID id,
        Type type,
        String canonicalUri,
        String objectKey,
        String contentType,
        long contentLength,
        String providerFileName
) {
    public static AnalysisSource youtube(String canonicalUri) {
        return new AnalysisSource(null, Type.YOUTUBE, canonicalUri, null, null, 0, null);
    }

    public enum Type { YOUTUBE, UPLOAD }
}
