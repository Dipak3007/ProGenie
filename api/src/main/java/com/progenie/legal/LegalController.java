package com.progenie.legal;

import java.util.List;

import com.progenie.legal.LegalDocuments.CompanyDto;
import com.progenie.legal.LegalDocuments.DocumentDto;
import com.progenie.legal.LegalDocuments.VersionDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public policy pages: /legal/terms, /legal/privacy, /legal/refunds, /legal/genie-agreement. */
@RestController
@RequestMapping("/api/v1/legal")
public class LegalController {

    private final LegalDocuments documents;

    public LegalController(LegalDocuments documents) {
        this.documents = documents;
    }

    /** Company and grievance-officer details shown on the Privacy page and Contact Us. */
    @GetMapping("/company")
    public CompanyDto company() {
        return documents.company();
    }

    @GetMapping("/{kind}")
    public DocumentDto current(@PathVariable String kind) {
        return documents.current(LegalKind.parse(kind));
    }

    @GetMapping("/{kind}/versions")
    public List<VersionDto> versions(@PathVariable String kind) {
        return documents.versions(LegalKind.parse(kind));
    }

    @GetMapping("/{kind}/versions/{version}")
    public DocumentDto version(@PathVariable String kind, @PathVariable int version) {
        return documents.version(LegalKind.parse(kind), version);
    }
}
