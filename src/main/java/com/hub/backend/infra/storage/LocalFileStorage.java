package com.hub.backend.infra.storage;

import com.hub.backend.common.config.properties.StorageProperties;
import com.hub.backend.common.exception.StorageException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Filesystem backed {@link FileStorage}.
 *
 * <p>Keys are laid out as {@code yyyy/MM/dd/<uuid>.<ext>} so a directory listing stays manageable
 * and no client supplied name ever reaches the filesystem. Every path is normalised and checked
 * against the root, which blocks {@code ../} traversal through a crafted key.</p>
 */
@Component
public class LocalFileStorage implements FileStorage {

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final Path root;

    public LocalFileStorage(StorageProperties properties) {
        this.root = Path.of(properties.getLocalRoot()).toAbsolutePath().normalize();
    }

    @Override
    public StoredObject store(InputStream content, String extension) {
        String storageKey = buildKey(extension);
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream source = content;
                 DigestOutputStream sink = new DigestOutputStream(
                         Files.newOutputStream(target, StandardOpenOption.CREATE_NEW), digest)) {
                size = source.transferTo(sink);
            }
            return new StoredObject(storageKey, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException exception) {
            throw new StorageException("Unable to store the attachment", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new StorageException("SHA-256 is not available in this runtime", exception);
        }
    }

    @Override
    public Resource load(String storageKey) {
        Path path = resolve(storageKey);
        if (!Files.isRegularFile(path)) {
            throw new StorageException("The stored attachment is no longer available");
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            throw new StorageException("Unable to delete the attachment", exception);
        }
    }

    private String buildKey(String extension) {
        String suffix = extension == null || extension.isBlank() ? "" : "." + extension.toLowerCase();
        return LocalDate.now().format(DATE_PATH) + "/" + UUID.randomUUID() + suffix;
    }

    /**
     * @throws StorageException when the key points outside the configured root
     */
    private Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new StorageException("Invalid storage key");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new StorageException("Invalid storage key");
        }
        return resolved;
    }
}
