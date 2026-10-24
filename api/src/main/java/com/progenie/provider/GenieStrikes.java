package com.progenie.provider;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.util.QueryUtil;

/**
 * Warnings on a Genie from resolved complaints. Three strikes within 90 days raise the reliability flag,
 * so an admin reviews the Genie (the same flag as for repeated cancellations).
 */
@Service
public class GenieStrikes {

    static final int THRESHOLD = 3;

    private final JdbcClient jdbc;
    private final GenieProfileService profiles;

    public GenieStrikes(JdbcClient jdbc, GenieProfileService profiles) {
        this.jdbc = jdbc;
        this.profiles = profiles;
    }

    /** Records a strike and returns how many the Genie has in the last 90 days. */
    @Transactional
    public int add(UUID genieId, UUID ticketId, String reason, UUID adminId) {
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_GENIE_STRIKE_ADD))
            .param("g", genieId).param("t", ticketId).param("r", reason).param("a", adminId).update();
        int recent = count(genieId);
        if (recent >= THRESHOLD) {
            profiles.flag(genieId, recent + " complaint strikes in 90 days");
        }
        return recent;
    }

    @Transactional(readOnly = true)
    public int count(UUID genieId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.COUNT_GENIE_STRIKE_RECENT))
            .param("g", genieId).query(Integer.class).single();
    }
}
