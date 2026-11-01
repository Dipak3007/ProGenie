package com.progenie.admin;

import java.util.List;

import com.progenie.shared.security.CurrentUser;
import com.progenie.support.HelpCentre;
import com.progenie.support.HelpCentre.AdminHelpArticleDto;
import com.progenie.support.HelpCentre.HelpArticleRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin editor for the help centre (Phase 3.5B): every answer, published or not, with its helpful counts. */
@RestController
@RequestMapping("/api/v1/admin/help")
public class AdminHelpController {

    private final HelpCentre help;

    public AdminHelpController(HelpCentre help) {
        this.help = help;
    }

    @GetMapping
    public List<AdminHelpArticleDto> all() {
        return help.all();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminHelpArticleDto create(@Valid @RequestBody HelpArticleRequest req) {
        return help.create(CurrentUser.id(), req);
    }

    @PutMapping("/{id}")
    public AdminHelpArticleDto update(@PathVariable long id, @Valid @RequestBody HelpArticleRequest req) {
        return help.update(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        help.delete(CurrentUser.id(), id);
    }
}
