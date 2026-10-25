package com.progenie.customer;

import java.util.List;
import java.util.UUID;

import com.progenie.provider.GenieDtos.GenieCardDto;
import com.progenie.provider.GenieQueryService;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A customer's saved ("favourite") Genies, for quick re-booking. */
@Service
public class FavouriteService {

    private final JdbcClient jdbc;
    private final GenieQueryService genies;

    public FavouriteService(JdbcClient jdbc, GenieQueryService genies) {
        this.jdbc = jdbc;
        this.genies = genies;
    }

    @Transactional(readOnly = true)
    public List<GenieCardDto> list(UUID customerId) {
        List<UUID> ids = jdbc.sql(QueryUtil.get(QueryConstants.GET_FAVOURITE_GENIE_IDS))
            .param("id", customerId)
            .query(UUID.class)
            .list();
        return genies.cards(ids);
    }

    @Transactional
    public void add(UUID customerId, UUID genieId) {
        if (!genies.isListed(genieId)) {
            throw ApiException.notFound("GENIE_NOT_FOUND", "Genie not found");
        }
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_FAVOURITE_ADD))
            .param("c", customerId).param("g", genieId).update();
    }

    @Transactional
    public void remove(UUID customerId, UUID genieId) {
        jdbc.sql(QueryUtil.get(QueryConstants.DELETE_FAVOURITE_REMOVE))
            .param("c", customerId).param("g", genieId).update();
    }
}
