package com.hub.backend.infra.storage;

import com.hub.backend.common.config.properties.StorageProperties;
import com.hub.backend.common.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileStorageTest {

    @TempDir
    Path root;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setLocalRoot(root.toString());
        storage = new LocalFileStorage(properties);
    }

    @Test
    void storesTheBlobWithItsSizeAndChecksum() throws IOException {
        byte[] payload = "hello attachment".getBytes(StandardCharsets.UTF_8);

        StoredObject stored = storage.store(new ByteArrayInputStream(payload), "TXT");

        assertEquals(payload.length, stored.sizeBytes());
        assertEquals(sha256Hex(payload), stored.checksumSha256());
        assertTrue(stored.storageKey().endsWith(".txt"), stored.storageKey());
        assertTrue(Files.exists(root.resolve(stored.storageKey())));

        Resource loaded = storage.load(stored.storageKey());
        assertArrayEquals(payload, loaded.getInputStream().readAllBytes());
    }

    @Test
    void partitionsKeysByUploadDate() {
        StoredObject stored = storage.store(new ByteArrayInputStream(new byte[]{1, 2, 3}), "png");

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertTrue(stored.storageKey().startsWith(today + "/"), stored.storageKey());
    }

    @Test
    void toleratesAMissingExtension() {
        StoredObject stored = storage.store(new ByteArrayInputStream(new byte[]{1}), null);

        assertFalse(stored.storageKey().contains("."));
    }

    @Test
    void deletesTheBlobAndIgnoresAnAbsentKey() {
        StoredObject stored = storage.store(new ByteArrayInputStream(new byte[]{9}), "txt");

        storage.delete(stored.storageKey());
        assertFalse(Files.exists(root.resolve(stored.storageKey())));

        // deleting twice is not an error
        storage.delete(stored.storageKey());
    }

    @Test
    void rejectsKeysThatEscapeTheRoot() {
        assertThrows(StorageException.class, () -> storage.delete("../outside.txt"));
        assertThrows(StorageException.class, () -> storage.load("../outside.txt"));
        assertThrows(StorageException.class, () -> storage.load(""));
    }

    @Test
    void reportsAMissingBlob() {
        StorageException exception = assertThrows(StorageException.class, () -> storage.load("2026/01/01/gone.txt"));

        assertTrue(exception.getMessage().contains("no longer available"));
    }

    private static String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
