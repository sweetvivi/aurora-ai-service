package com.hub.backend.infra.storage;

/**
 * Result of writing a blob into the store.
 *
 * @param storageKey     opaque key to store in the database and use to read the blob back
 * @param sizeBytes      number of bytes actually written
 * @param checksumSha256 lower case hex SHA-256 of the stored bytes
 */
public record StoredObject(String storageKey, long sizeBytes, String checksumSha256) {
}
