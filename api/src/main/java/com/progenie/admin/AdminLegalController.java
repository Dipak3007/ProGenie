package com.progenie.admin;

import java.util.List;
import java.util.UUID;

import com.progenie.legal.LegalDocuments;
import com.progenie.legal.LegalDocuments.DocumentDto;
import com.progenie.legal.LegalDocuments.DraftRequest;
import com.progenie.legal.LegalKind;
import com.progenie.shared.security.CurrentUser;
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

/** Edit and publish policy documents. Published versions are read-only. */
@RestController
@RequestMapping("/api/v1/admin/legal")
public class AdminLegalController {

    private final LegalDocuments documents;

    public AdminLegalController(LegalDocuments documents) {
        this.documents = documents;
    }

    @GetMapping
    public List<DocumentDto> list() {
        return documents.adminList();
    }

    @PutMapping("/{kind}/draft")
    public DocumentDto saveDraft(@PathVariable String kind, @RequestBody DraftRequest req) {
        return documents.saveDraft(LegalKind.parse(kind), req, CurrentUser.id());
    }

    @DeleteMapping("/{kind}/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@PathVariable String kind) {
        documents.deleteDraft(LegalKind.parse(kind));
    }

    @PostMapping("/{id}/publish")
    public DocumentDto publish(@PathVariable UUID id) {
        return documents.publish(id);
    }
}
