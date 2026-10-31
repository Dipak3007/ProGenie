package com.progenie.it;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A tiny HTTP client for integration tests: JSON in, JSON out, with an optional bearer token. */
final class ApiClient {

    record Response(int status, JsonNode body, byte[] raw) {
    }

    static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final String base;
    String token;

    ApiClient(int port) {
        this.base = "http://localhost:" + port;
    }

    ApiClient login(String identifier) {
        Response r = post("/api/v1/auth/login", "{\"identifier\":\"" + identifier + "\",\"password\":\"ProGenie@123\"}");
        if (r.status() != 200) {
            throw new IllegalStateException("login " + identifier + " failed: " + r.status() + " " + r.body());
        }
        token = r.body().get("accessToken").asString();
        return this;
    }

    Response get(String path) {
        return send(request(path).GET());
    }

    Response post(String path, String json) {
        return send(request(path).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json)));
    }

    Response put(String path, String json) {
        return send(request(path).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json)));
    }

    Response delete(String path) {
        return send(request(path).DELETE());
    }

    /** POST one file as multipart/form-data. */
    Response upload(String path, String field, String fileName, String contentType, byte[] content) {
        String boundary = "----progenie-it-" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\""
            + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        body.writeBytes(head);
        body.writeBytes(content);
        body.writeBytes(tail);
        return send(request(path).header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
    }

    /** GET an absolute or site-relative URL without the token (like a browser opening a download link). */
    Response open(String url) {
        String full = url.startsWith("http") ? url : base + url;
        return send(HttpRequest.newBuilder(URI.create(full)).timeout(Duration.ofSeconds(20)).GET());
    }

    HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private Response send(HttpRequest.Builder b) {
        try {
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] raw = res.body();
            JsonNode node = null;
            if (res.headers().firstValue("Content-Type").orElse("").contains("json") && raw.length > 0) {
                node = JSON.readTree(raw);
            }
            return new Response(res.statusCode(), node, raw);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    HttpClient http() {
        return http;
    }
}
