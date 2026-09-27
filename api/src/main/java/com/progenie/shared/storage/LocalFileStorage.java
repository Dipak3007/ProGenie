package com.progenie.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
import org.springframework.stereotype.Component;

/** Stores files under {@code progenie.storage.local-dir}. Keys can never escape that folder. */
@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(AppProperties props) {
        this.root = Path.of(props.storage().localDir()).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, InputStream content, long size, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store " + key, e);
        }
    }

    @Override
    public InputStream get(String key) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            throw ApiException.notFound("FILE_NOT_FOUND", "The file is not available");
        }
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + key, e);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw ApiException.badRequest("INVALID_KEY", "Invalid storage key");
        }
        return path;
    }
}
