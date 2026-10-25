package com.progenie.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stores files under {@code progenie.storage.local-dir}. Keys can never escape that folder. Download links are
 * signed {@code /api/v1/files/...} paths served by {@link FileLinkController}. The default adapter; on a cloud
 * container the folder is lost on restart, so the SIT uses {@link S3FileStorage}.
 */
@Component
@ConditionalOnProperty(name = "progenie.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private final Path root;
    private final LinkSigner signer;
    private final Duration linkTtl;
    private final Clock clock;

    public LocalFileStorage(AppProperties props, LinkSigner signer, Clock clock) {
        this.root = Path.of(props.storage().localDir()).toAbsolutePath().normalize();
        this.signer = signer;
        this.linkTtl = props.storage().linkTtl() == null ? Duration.ofMinutes(5) : props.storage().linkTtl();
        this.clock = clock;
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
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public FileLink link(String key, String fileName, String contentType, boolean inline) {
        resolve(key); // reject bad keys now, not when the link is opened
        Instant expires = clock.instant().plus(linkTtl);
        String token = signer.sign(new LinkSigner.Target(key, fileName, contentType, inline, expires.getEpochSecond()));
        return new FileLink(FileLinkController.PATH + token, expires);
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
