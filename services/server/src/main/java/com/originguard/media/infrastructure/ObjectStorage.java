package com.originguard.media.infrastructure;

public interface ObjectStorage {
    void put(String objectKey, byte[] content, String contentType);

    byte[] get(String objectKey);

    void remove(String objectKey);
}
