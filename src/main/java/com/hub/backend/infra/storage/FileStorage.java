package com.hub.backend.infra.storage;

import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * Port for blob storage.
 *
 * <p>Business modules depend on this interface only, so the local filesystem implementation used
 * today can be swapped for S3/MinIO without touching {@code modules/file}. See
 * {@code docs/ai1foo-rebuild-plan.md} §8.4 for the object storage roadmap.</p>
 */
public interface FileStorage {

    /**
     * Stores the stream under a freshly generated key.
     *
     * @param content   stream to consume; the implementation closes it
     * @param extension file extension without the dot, may be {@code null}
     * @return key, size and checksum of the stored blob
     */
    StoredObject store(InputStream content, String extension);

    /**
     * @param storageKey key returned by {@link #store(InputStream, String)}
     * @return readable handle for the blob
     */
    Resource load(String storageKey);

    /**
     * Removes the blob. Deleting an absent key is not an error.
     */
    void delete(String storageKey);
}
