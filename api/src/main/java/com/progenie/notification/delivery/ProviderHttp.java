package com.progenie.notification.delivery;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

import com.progenie.notification.delivery.MessageSender.DeliveryException;
import com.progenie.shared.storage.FileStorage;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** HTTP plumbing shared by the provider adapters. */
final class ProviderHttp {

    private ProviderHttp() {
    }

    static RestClient client(String baseUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 429, 5xx and network errors are worth retrying; other 4xx are not. */
    static DeliveryException classify(String provider, RuntimeException ex) {
        if (ex instanceof RestClientResponseException r) {
            int code = r.getStatusCode().value();
            String body = r.getResponseBodyAsString();
            String msg = provider + " answered " + code + (body.isBlank() ? "" : ": " + body);
            return new DeliveryException(msg, code == 429 || code >= 500, ex);
        }
        if (ex instanceof ResourceAccessException) {
            return new DeliveryException(provider + " unreachable: " + ex.getMessage(), true, ex);
        }
        if (ex instanceof DeliveryException d) {
            return d;
        }
        return new DeliveryException(provider + " error: " + ex.getMessage(), true, ex);
    }

    static String base64(FileStorage storage, String key) {
        try (InputStream in = storage.get(key)) {
            return Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            throw new DeliveryException("Attachment " + key + " can't be read: " + e.getMessage(), false, e);
        }
    }

    static String contentType(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (name.endsWith(".zip")) {
            return "application/zip";
        }
        return "application/octet-stream";
    }
}
