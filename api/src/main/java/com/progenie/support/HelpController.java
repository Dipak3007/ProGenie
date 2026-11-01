package com.progenie.support;

import java.util.List;

import com.progenie.shared.security.CurrentUser;
import com.progenie.support.HelpCentre.HelpArticleDto;
import com.progenie.support.HelpCentre.VoteRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The public help centre (Phase 3.5B). */
@RestController
public class HelpController {

    private final HelpCentre help;

    public HelpController(HelpCentre help) {
        this.help = help;
    }

    @GetMapping("/api/v1/help")
    public List<HelpArticleDto> articles() {
        return help.published();
    }

    /** Up to 3 answers for a complaint category ("Report a problem" shows them first). */
    @GetMapping("/api/v1/help/suggestions")
    public List<HelpArticleDto> suggestions(@RequestParam TicketCategory ticketCategory) {
        return help.suggestions(ticketCategory);
    }

    /** "Was this helpful?" Anonymous; counted once a day per person (or address) and answer. */
    @PostMapping("/api/v1/help/{id}/vote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void vote(@PathVariable long id, @Valid @RequestBody VoteRequest req, HttpServletRequest http) {
        var user = CurrentUser.idOrNull();
        help.vote(id, req.helpful(), user != null ? user.toString() : String.valueOf(http.getRemoteAddr()));
    }
}
