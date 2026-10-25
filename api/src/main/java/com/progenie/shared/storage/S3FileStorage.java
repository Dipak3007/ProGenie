package com.progenie.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;

/**
 * Files in an S3-compatible bucket: Supabase Storage on the free SIT, SeaweedFS locally, AWS S3 or any other
 * S3 API later. Uses the JDK HTTP client and {@link SigV4}, no SDK. The bucket stays private: the browser only
 * ever gets presigned GET URLs that expire after {@code progenie.storage.link-ttl}.
 */
@Component
@ConditionalOnProperty(name = "progenie.storage.type", havingValue = "s3")
public class S3FileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final AppProperties.S3 s3;
    private final SigV4 signer;
    private final Duration linkTtl;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public S3FileStorage(AppProperties props, Clock clock) {
        this.s3 = props.storage().s3();
        if (s3 == null || blank(s3.endpoint()) || blank(s3.bucket()) || blank(s3.accessKey()) || blank(s3.secretKey())) {
            throw new IllegalStateException("progenie.storage.type=s3 needs S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY and S3_SECRET_KEY");
        }
        this.signer = new SigV4(s3.accessKey(), s3.secretKey(), blank(s3.region()) ? "us-east-1" : s3.region());
        this.linkTtl = props.storage().linkTtl() == null ? Duration.ofMinutes(5) : props.storage().linkTtl();
        this.clock = clock;
        log.info("File storage: S3 bucket '{}' at {}", s3.bucket(), s3.endpoint());
        if (s3.createBucket()) {
            createBucketIfMissing();
        }
    }

    /** Local convenience (SeaweedFS): PUT /{bucket} when it doesn't exist yet. Never used against Supabase. */
    private void createBucketIfMissing() {
        URI bucket = URI.create(s3.endpoint().replaceAll("/+$", "") + "/" + SigV4.encode(s3.bucket()));
        try {
            HttpResponse<Void> head = send(builder("HEAD", bucket, Map.of(), SigV4.UNSIGNED)
                .method("HEAD", HttpRequest.BodyPublishers.noBody()), HttpResponse.BodyHandlers.discarding());
            if (head.statusCode() == 404) {
                HttpResponse<String> res = send(builder("PUT", bucket, Map.of(), SigV4.sha256Hex(new byte[0]))
                    .PUT(HttpRequest.BodyPublishers.noBody()), HttpResponse.BodyHandlers.ofString());
                log.info("Created bucket '{}': HTTP {}", s3.bucket(), res.statusCode());
            }
        } catch (RuntimeException e) {
            log.warn("Could not check or create bucket '{}': {}", s3.bucket(), e.getMessage());
        }
    }

    @Override
    public void put(String key, InputStream content, long size, String contentType) {
        byte[] body;
        try {
            body = content.readAllBytes(); // files are small (5 MB upload limit); hashing needs the bytes anyway
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + key, e);
        }
        URI uri = objectUri(key);
        Map<String, String> extra = Map.of("content-type", contentType == null ? "application/octet-stream" : contentType);
        HttpResponse<String> res = send(builder("PUT", uri, extra, SigV4.sha256Hex(body))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("S3 PUT " + key + " failed: HTTP " + res.statusCode() + " " + abbreviate(res.body()));
        }
    }

    @Override
    public InputStream get(String key) {
        HttpResponse<InputStream> res = send(builder("GET", objectUri(key), Map.of(), SigV4.UNSIGNED).GET(),
            HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() == 404 || res.statusCode() == 400 && isNoSuchKey(res)) {
            close(res.body());
            throw ApiException.notFound("FILE_NOT_FOUND", "The file is not available");
        }
        if (res.statusCode() / 100 != 2) {
            close(res.body());
            throw new IllegalStateException("S3 GET " + key + " failed: HTTP " + res.statusCode());
        }
        return res.body();
    }

    @Override
    public boolean exists(String key) {
        HttpResponse<Void> res = send(builder("HEAD", objectUri(key), Map.of(), SigV4.UNSIGNED)
            .method("HEAD", HttpRequest.BodyPublishers.noBody()), HttpResponse.BodyHandlers.discarding());
        if (res.statusCode() == 404 || res.statusCode() == 400) {
            return false;
        }
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("S3 HEAD " + key + " failed: HTTP " + res.statusCode());
        }
        return true;
    }

    @Override
    public void delete(String key) {
        HttpResponse<String> res = send(builder("DELETE", objectUri(key), Map.of(), SigV4.UNSIGNED).DELETE(),
            HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2 && res.statusCode() != 404) {
            throw new IllegalStateException("S3 DELETE " + key + " failed: HTTP " + res.statusCode() + " " + abbreviate(res.body()));
        }
    }

    @Override
    public FileLink link(String key, String fileName, String contentType, boolean inline) {
        Instant now = clock.instant();
        Map<String, String> params = new LinkedHashMap<>();
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(fileName, java.nio.charset.StandardCharsets.UTF_8).build();
        params.put("response-content-disposition", disposition.toString());
        if (contentType != null) {
            params.put("response-content-type", contentType);
        }
        params.put("response-cache-control", "private, no-store");
        String publicBase = blank(s3.publicEndpoint()) ? s3.endpoint() : s3.publicEndpoint();
        URI url = signer.presignGet(objectUri(key, publicBase), params, linkTtl, now);
        return new FileLink(url.toString(), now.plus(linkTtl));
    }

    /** {endpoint}/{bucket}/{key} (path style) or {scheme}://{bucket}.{host}/{key}. */
    URI objectUri(String key) {
        return objectUri(key, s3.endpoint());
    }

    private URI objectUri(String key, String endpointBase) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("..")) {
            throw ApiException.badRequest("INVALID_KEY", "Invalid storage key");
        }
        String endpoint = endpointBase.replaceAll("/+$", "");
        if (s3.pathStyle()) {
            return URI.create(endpoint + "/" + SigV4.encode(s3.bucket()) + "/" + SigV4.encodePath(key));
        }
        URI base = URI.create(endpoint);
        String authority = s3.bucket() + "." + base.getRawAuthority();
        return URI.create(base.getScheme() + "://" + authority + base.getRawPath() + "/" + SigV4.encodePath(key));
    }

    private HttpRequest.Builder builder(String method, URI uri, Map<String, String> extra, String payloadSha) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(TIMEOUT);
        signer.sign(method, uri, extra, payloadSha, clock.instant()).forEach(b::header);
        return b;
    }

    private <T> HttpResponse<T> send(HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request.build(), handler);
        } catch (IOException e) {
            throw new UncheckedIOException("S3 request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling S3", e);
        }
    }

    /** Supabase answers a missing object with 400 + NoSuchKey in some versions. */
    private static boolean isNoSuchKey(HttpResponse<InputStream> res) {
        try (InputStream in = res.body()) {
            return new String(in.readNBytes(2048), java.nio.charset.StandardCharsets.UTF_8).contains("NoSuchKey");
        } catch (IOException e) {
            return false;
        }
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
