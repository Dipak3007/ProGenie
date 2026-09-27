package com.progenie.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** "Contact Us" form. Messages land in contact_messages for the admin inbox. */
@RestController
public class ContactController {

    public record ContactRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 150) String email,
        @Size(max = 15) String phone,
        @NotBlank @Size(max = 150) String subject,
        @NotBlank @Size(max = 2000) String message) {
    }

    private final JdbcClient jdbc;

    public ContactController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/api/v1/contact")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void submit(@Valid @RequestBody ContactRequest req) {
        jdbc.sql("""
                INSERT INTO contact_messages (name, email, phone, subject, message)
                VALUES (:name, :email, :phone, :subject, :message)
                """)
            .param("name", req.name().trim())
            .param("email", req.email().trim().toLowerCase())
            .param("phone", req.phone())
            .param("subject", req.subject().trim())
            .param("message", req.message().trim())
            .update();
    }
}
