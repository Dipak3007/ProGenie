package com.progenie.legal;

import java.util.List;
import java.util.Map;

import com.progenie.legal.ConsentService.ConsentDto;
import com.progenie.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The logged-in user's policy acceptances (any role). */
@RestController
@RequestMapping("/api/v1/me/consents")
public class ConsentController {

    public record AcceptRequest(@NotEmpty List<String> kinds) {
    }

    private final ConsentService consents;

    public ConsentController(ConsentService consents) {
        this.consents = consents;
    }

    @GetMapping
    public List<ConsentDto> history() {
        return consents.history(CurrentUser.id());
    }

    /** Accepts the current version of the given policies; returns what is still pending. */
    @PostMapping
    public Map<String, List<String>> accept(@Valid @RequestBody AcceptRequest req, HttpServletRequest http,
                                            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String ua) {
        List<LegalKind> kinds = req.kinds().stream().map(LegalKind::parse).toList();
        return Map.of("pendingConsents",
            consents.accept(CurrentUser.id(), CurrentUser.role(), kinds, http.getRemoteAddr(), ua));
    }
}
