package com.vid2knowledge.storage;

import java.net.URI;
import java.io.InputStream;
import java.time.Duration;

public interface ObjectStorage {
    URI presignPut(String objectKey, String contentType, long contentLength, Duration duration);
    StoredObject head(String objectKey);
    byte[] readPrefix(String objectKey, int length);
    InputStream open(String objectKey);
    void copy(String sourceObjectKey, String destinationObjectKey);
    URI presignGet(String objectKey, Duration duration);
    void delete(String objectKey);

    record StoredObject(long contentLength, String contentType, String eTag) {}
}
