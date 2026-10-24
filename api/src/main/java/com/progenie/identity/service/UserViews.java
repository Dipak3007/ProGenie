package com.progenie.identity.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.progenie.identity.domain.User;
import com.progenie.identity.web.dto.AuthDtos.UserDto;
import com.progenie.legal.ConsentService;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.util.QueryUtil;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Builds the account view returned by login and /me, including what the app must ask the user next. */
@Component
public class UserViews {

    private final ConsentService consents;
    private final JdbcClient jdbc;

    public UserViews(ConsentService consents, JdbcClient jdbc) {
        this.consents = consents;
        this.jdbc = jdbc;
    }

    public UserDto of(User u) {
        List<String> pending = consents.pendingKinds(u.getId(), u.getRole().name());
        OffsetDateTime deletion = jdbc.sql(QueryUtil.get(QueryConstants.GET_DELETION_SCHEDULED_FOR))
            .param("u", u.getId()).query(OffsetDateTime.class).optional().orElse(null);
        return new UserDto(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole().name(),
            u.isPhoneVerified(), u.isEmailVerified(), pending, deletion);
    }
}
