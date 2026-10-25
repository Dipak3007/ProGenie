package com.progenie.identity.domain;

/** Exposed to other modules (e.g. provider reacts to GENIE sign-ups). */
@org.springframework.modulith.NamedInterface("model")
public enum Role {
    CUSTOMER,
    GENIE,
    ADMIN
}
