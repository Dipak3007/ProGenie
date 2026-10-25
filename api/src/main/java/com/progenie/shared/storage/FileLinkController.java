package com.progenie.shared.storage;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves local download links ({@link LocalFileStorage#link}). No login: the signed, expiring token is the
 * permission, exactly like an S3 presigned URL. Not used when files live in S3 (the browser goes to the bucket).
 */
@RestController
public class FileLinkController {

    public static final String PATH = "/api/v1/files/";

    private final LinkSigner signer;
    private final FileStorage storage;

    public FileLinkController(LinkSigner signer, FileStorage storage) {
        this.signer = signer;
        this.storage = storage;
    }

    @GetMapping(PATH + "{token}")
    public ResponseEntity<InputStreamResource> file(@PathVariable String token) {
        LinkSigner.Target t = signer.verify(token);
        ContentDisposition disposition = (t.i() ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(t.n(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
            .contentType(t.t() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(t.t()))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(new InputStreamResource(storage.get(t.k())));
    }
}
