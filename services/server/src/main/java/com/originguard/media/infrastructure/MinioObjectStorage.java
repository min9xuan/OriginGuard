package com.originguard.media.infrastructure;

import com.originguard.shared.application.BusinessConflictException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import org.springframework.stereotype.Component;

@Component
public class MinioObjectStorage implements ObjectStorage {
    private final MinioClient client;
    private final StorageProperties properties;

    public MinioObjectStorage(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .contentType(contentType)
                    .stream(new ByteArrayInputStream(content), (long) content.length, -1L)
                    .build());
        } catch (Exception exception) {
            throw storageFailure("store", exception);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try (var stream = client.getObject(GetObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .build())) {
            return stream.readAllBytes();
        } catch (Exception exception) {
            throw storageFailure("read", exception);
        }
    }

    @Override
    public void remove(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw storageFailure("remove", exception);
        }
    }

    private void ensureBucket() throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder()
                .bucket(properties.bucket())
                .build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
        }
    }

    private BusinessConflictException storageFailure(String action, Exception exception) {
        return new BusinessConflictException(
                "OBJECT_STORAGE_FAILURE",
                "Unable to " + action + " media object: " + exception.getClass().getSimpleName());
    }
}
