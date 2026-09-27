package com.progenie.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.progenie.shared.error.ApiException;
import org.junit.jupiter.api.Test;

class LegalKindTest {

    @Test
    void parsesSlugsAndNames() {
        assertThat(LegalKind.parse("terms")).isEqualTo(LegalKind.TERMS);
        assertThat(LegalKind.parse("genie-agreement")).isEqualTo(LegalKind.GENIE_AGREEMENT);
        assertThat(LegalKind.parse("GENIE_AGREEMENT")).isEqualTo(LegalKind.GENIE_AGREEMENT);
        assertThatThrownBy(() -> LegalKind.parse("cookies")).isInstanceOf(ApiException.class);
    }

    @Test
    void requiredPoliciesDependOnTheRole() {
        assertThat(ConsentService.requiredKinds("CUSTOMER")).containsExactly(LegalKind.TERMS, LegalKind.PRIVACY);
        assertThat(ConsentService.requiredKinds("GENIE")).contains(LegalKind.GENIE_AGREEMENT);
        assertThat(ConsentService.requiredKinds("ADMIN")).isEmpty();
    }
}
