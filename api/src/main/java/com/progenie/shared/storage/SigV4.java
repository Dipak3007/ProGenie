package com.progenie.shared.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 for S3, written out instead of pulling in the AWS SDK (which would add tens of MB and
 * a lot of memory on a 512 MB instance). It covers exactly what {@link S3FileStorage} needs: signed requests
 * (Authorization header) and presigned GET URLs. Checked against the examples in the AWS S3 documentation.
 */
final class SigV4 {

    static final String UNSIGNED = "UNSIGNED-PAYLOAD";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    private final String accessKey;
    private final String secretKey;
    private final String region;

    SigV4(String accessKey, String secretKey, String region) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }

    /**
     * Headers to send with a request: x-amz-date, x-amz-content-sha256 and Authorization.
     *
     * @param extraHeaders other headers to sign (lower-case names), e.g. content-type
     */
    Map<String, String> sign(String method, URI uri, Map<String, String> extraHeaders, String payloadSha256, Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String day = amzDate.substring(0, 8);
        TreeMap<String, String> headers = new TreeMap<>();
        extraHeaders.forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.trim()));
        headers.put("host", hostHeader(uri));
        headers.put("x-amz-content-sha256", payloadSha256);
        headers.put("x-amz-date", amzDate);
        String signedHeaders = String.join(";", headers.keySet());
        String canonical = method + "\n" + uri.getRawPath() + "\n" + canonicalQuery(uri.getRawQuery()) + "\n"
            + headers.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue() + "\n").collect(Collectors.joining())
            + "\n" + signedHeaders + "\n" + payloadSha256;
        String scope = day + "/" + region + "/s3/aws4_request";
        String signature = signature(day, "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonical));
        TreeMap<String, String> out = new TreeMap<>(headers);
        out.remove("host"); // the HTTP client sets it
        out.put("authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
            + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return out;
    }

    /**
     * Presigned GET URL. {@code params} are extra query parameters (e.g. response-content-disposition), not yet
     * encoded. Only the host header is signed, so the browser can fetch it with no other headers.
     */
    URI presignGet(URI objectUri, Map<String, String> params, Duration ttl, Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String day = amzDate.substring(0, 8);
        String scope = day + "/" + region + "/s3/aws4_request";
        TreeMap<String, String> query = new TreeMap<>();
        params.forEach((k, v) -> query.put(encode(k), encode(v)));
        query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        query.put("X-Amz-Credential", encode(accessKey + "/" + scope));
        query.put("X-Amz-Date", amzDate);
        query.put("X-Amz-Expires", Long.toString(ttl.toSeconds()));
        query.put("X-Amz-SignedHeaders", "host");
        String rawQuery = query.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
        String canonical = "GET\n" + objectUri.getRawPath() + "\n" + rawQuery + "\nhost:" + hostHeader(objectUri)
            + "\n\nhost\n" + UNSIGNED;
        String signature = signature(day, "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonical));
        return URI.create(objectUri.toString() + "?" + rawQuery + "&X-Amz-Signature=" + signature);
    }

    private String signature(String day, String stringToSign) {
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        return HEX.formatHex(hmac(key, stringToSign));
    }

    /** Host header as the server sees it: no port when it is the scheme's default. */
    static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("https".equals(uri.getScheme()) && port == 443) || ("http".equals(uri.getScheme()) && port == 80);
        return defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
    }

    /** Sorted query string; the values in a raw query are already encoded. */
    private static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            sorted.put(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
        }
        return sorted.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
    }

    /** RFC 3986 encoding as AWS wants it: unreserved characters stay, everything else is %XX (upper case). */
    static String encode(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(HEX.withUpperCase().toHexDigits(b));
            }
        }
        return sb.toString();
    }

    /** Encodes an object key for the URL path: each segment encoded, the slashes kept. */
    static String encodePath(String key) {
        return java.util.Arrays.stream(key.split("/", -1)).map(SigV4::encode).collect(Collectors.joining("/"));
    }

    static String sha256Hex(byte[] data) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
