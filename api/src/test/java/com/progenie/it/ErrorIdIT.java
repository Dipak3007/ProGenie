package com.progenie.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * Error IDs and request logging (3A-0): every error reply carries an errorId equal to the X-Request-Id header,
 * whoever produced it (our handlers, Spring MVC, Spring Security), and that ID is on the matching log lines.
 */
@ExtendWith(OutputCaptureExtension.class)
class ErrorIdIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void aBusinessErrorHasAnErrorIdThatIsInTheLogs(CapturedOutput output) {
        HttpResponse<String> r = get("/api/v1/serviceability?pincode=12345", null, null);
        assertThat(r.statusCode()).isEqualTo(400);
        String id = r.headers().firstValue("X-Request-Id").orElseThrow();
        JsonNode body = ApiClient.JSON.readTree(r.body());
        assertThat(body.get("code").asString()).isEqualTo("INVALID_PINCODE");
        assertThat(body.get("errorId").asString()).isEqualTo(id).matches("[0-9A-Z]{12}");
        assertThat(output.getOut()).contains("[" + id + "|").contains("400 INVALID_PINCODE");
    }

    @Test
    void anIncomingRequestIdIsKeptAndAJunkOneIsReplaced() {
        HttpResponse<String> kept = get("/api/v1/serviceability?pincode=12345", "8f1c2a3b4c5d6e7f-SIN", null);
        assertThat(kept.headers().firstValue("X-Request-Id")).contains("8f1c2a3b4c5d6e7f-SIN");
        assertThat(ApiClient.JSON.readTree(kept.body()).get("errorId").asString()).isEqualTo("8f1c2a3b4c5d6e7f-SIN");

        HttpResponse<String> junk = get("/api/v1/cities", "<script>", null);
        assertThat(junk.headers().firstValue("X-Request-Id").orElseThrow()).matches("[0-9A-Z]{12}");
    }

    @Test
    void securityAndFrameworkErrorsGetOneToo(CapturedOutput output) {
        HttpResponse<String> noLogin = get("/api/v1/me", null, null);
        assertThat(noLogin.statusCode()).isEqualTo(401);
        JsonNode body = ApiClient.JSON.readTree(noLogin.body());
        assertThat(body.get("code").asString()).isEqualTo("NOT_AUTHENTICATED");
        assertThat(body.get("errorId").asString()).isEqualTo(noLogin.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(noLogin.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Bearer");

        String token = new ApiClient(port).login("customer@example.com").token;
        HttpResponse<String> notAdmin = get("/api/v1/admin/users", null, token);
        assertThat(notAdmin.statusCode()).isEqualTo(403);
        assertThat(ApiClient.JSON.readTree(notAdmin.body()).get("errorId").asString()).isNotBlank();

        HttpResponse<String> noSuchPath = get("/api/v1/no-such-thing", null, token);
        assertThat(noSuchPath.statusCode()).isEqualTo(404);
        assertThat(ApiClient.JSON.readTree(noSuchPath.body()).get("errorId").asString())
            .isEqualTo(noSuchPath.headers().firstValue("X-Request-Id").orElseThrow());

        // a logged-in request's lines carry the user's ID
        String id = notAdmin.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(output.getOut()).containsPattern("\\[" + id + "\\|[0-9a-f-]{36}\\]");
    }

    @Test
    void businessEventsAreLoggedWithoutPersonalData(CapturedOutput output) {
        new ApiClient(port).post("/api/v1/auth/login", "{\"identifier\":\"9825000005\",\"password\":\"wrong-password\"}");
        assertThat(output.getOut()).contains("Login failed for 98******05").doesNotContain("9825000005");
        new ApiClient(port).login("customer@example.com");
        assertThat(output.getOut()).containsPattern("Login: user [0-9a-f-]{36} \\(CUSTOMER\\) with password")
            .doesNotContain("ProGenie@123");
    }

    private HttpResponse<String> get(String path, String requestId, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
        if (requestId != null) {
            b.header("X-Request-Id", requestId);
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        try {
            return new ApiClient(port).http().send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
