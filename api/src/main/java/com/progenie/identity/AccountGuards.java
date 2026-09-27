package com.progenie.identity;

import java.util.UUID;

import com.progenie.identity.service.AuthProperties;
import com.progenie.shared.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Checks other modules run before sensitive actions (booking, submitting a Genie profile). */
@Component
public class AccountGuards {

    private final JdbcClient jdbc;
    private final AuthProperties props;

    public AccountGuards(JdbcClient jdbc, AuthProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** 403 PHONE_NOT_VERIFIED unless the user has confirmed their mobile number with a code. */
    @Transactional(readOnly = true)
    public void requireVerifiedPhone(UUID userId) {
        if (!props.requireVerifiedPhone()) {
            return;
        }
        Boolean verified = jdbc.sql("SELECT phone_verified FROM users WHERE id = :id")
            .param("id", userId).query(Boolean.class).optional().orElse(false);
        if (!Boolean.TRUE.equals(verified)) {
            throw ApiException.forbidden("PHONE_NOT_VERIFIED", "Please verify your mobile number first");
        }
    }
}
