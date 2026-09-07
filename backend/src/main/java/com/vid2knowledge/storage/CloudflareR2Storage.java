package com.vid2knowledge.storage;

import com.vid2knowledge.config.ObjectStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

@Component
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class CloudflareR2Storage implements ObjectStorage, AutoCloseable {
    private final ObjectStorageProperties properties;
    private final S3Client client;
    private final S3Presigner presigner;

    public CloudflareR2Storage(ObjectStorageProperties properties) {
        this.properties = properties;
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                properties.accessKeyId(), properties.secretAccessKey()
        ));
        var service = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build();
        this.client = S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of("auto"))
                .credentialsProvider(credentials)
                .serviceConfiguration(service)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of("auto"))
                .credentialsProvider(credentials)
                .serviceConfiguration(service)
                .build();
    }

    @Override
    public URI presignPut(String objectKey, String contentType, long contentLength, Duration duration) {
        var put = PutObjectRequest.builder().bucket(properties.bucket()).key(objectKey)
                .contentType(contentType).contentLength(contentLength).build();
        return URI.create(presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(duration).putObjectRequest(put).build()).url().toString());
    }

    @Override
    public StoredObject head(String objectKey) {
        var response = client.headObject(HeadObjectRequest.builder()
                .bucket(properties.bucket()).key(objectKey).build());
        return new StoredObject(response.contentLength(), response.contentType(), response.eTag());
    }

    @Override
    public byte[] readPrefix(String objectKey, int length) {
        if (length <= 0 || length > 4_096) throw new IllegalArgumentException("Invalid prefix length");
        return client.getObject(GetObjectRequest.builder().bucket(properties.bucket()).key(objectKey)
                .range("bytes=0-" + (length - 1)).build(), ResponseTransformer.toBytes()).asByteArray();
    }

    @Override
    public void delete(String objectKey) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
    }
}
