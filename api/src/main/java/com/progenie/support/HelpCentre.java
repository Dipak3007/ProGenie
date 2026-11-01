package com.progenie.support;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.ratelimit.RateLimiter;
import com.progenie.shared.util.QueryUtil;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The help centre (Phase 3.5B): questions and Markdown answers per category, public, edited by admins. "Report a
 * problem" suggests the answers tagged with the chosen complaint category first. "Was this helpful?" is counted per
 * answer, anonymously, once per person (or address) and answer a day.
 */
@Service
public class HelpCentre {

    private static final Logger log = LoggerFactory.getLogger(HelpCentre.class);

    /** In the order the help centre shows them. */
    public enum HelpCategory { BOOKING, CANCELLATIONS, PAYMENTS, GENIE_NOW, PROBLEMS, ACCOUNT, GENIES }

    public record HelpArticleDto(long id, String category, String slug, String question, String answer) {
    }

    public record AdminHelpArticleDto(long id, String category, String slug, String question, String answer,
                                      List<String> ticketCategories, int position, boolean published, int helpfulYes,
                                      int helpfulNo, OffsetDateTime updatedAt) {
    }

    public record HelpArticleRequest(@NotNull HelpCategory category,
                                     @NotBlank @Size(max = 80) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                                         message = "lower-case words joined by hyphens") String slug,
                                     @NotBlank @Size(max = 200) String question,
                                     @NotBlank @Size(max = 10_000) String answer,
                                     List<TicketCategory> ticketCategories,
                                     @Min(0) @Max(10_000) int position,
                                     boolean published) {
    }

    public record VoteRequest(boolean helpful) {
    }

    record AdminRow(long id, String category, String slug, String question, String answer, String ticketCategories,
                    int position, boolean published, int helpfulYes, int helpfulNo, OffsetDateTime updatedAt) {

        AdminHelpArticleDto toDto() {
            return new AdminHelpArticleDto(id, category, slug, question, answer,
                StringUtils.hasText(ticketCategories) ? List.of(ticketCategories.split(",")) : List.of(), position,
                published, helpfulYes, helpfulNo, updatedAt);
        }
    }

    private static final String ORDER = Arrays.stream(HelpCategory.values()).map(Enum::name)
        .collect(Collectors.joining(","));

    private final JdbcClient jdbc;
    private final RateLimiter limiter;

    public HelpCentre(JdbcClient jdbc, RateLimiter limiter) {
        this.jdbc = jdbc;
        this.limiter = limiter;
    }

    /** Every published answer, in category order. The page searches them itself (there are a few dozen). */
    @Transactional(readOnly = true)
    public List<HelpArticleDto> published() {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_HELP_PUBLISHED)).param("order", ORDER)
            .query(HelpArticleDto.class).list();
    }

    /** Up to 3 answers for a complaint category, shown before the "Report a problem" form. */
    @Transactional(readOnly = true)
    public List<HelpArticleDto> suggestions(TicketCategory category) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_HELP_SUGGESTIONS)).param("cat", category.name())
            .query(HelpArticleDto.class).list();
    }

    /** Counted once per person (or address) and answer a day; repeats are ignored quietly. */
    @Transactional
    public void vote(long id, boolean helpful, String who) {
        if (!limiter.tryAcquire("help-vote:" + who + ":" + id, 1, Duration.ofDays(1))) {
            return;
        }
        int rows = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_HELP_VOTE)).param("id", id).param("yes", helpful ? 1 : 0)
            .param("no", helpful ? 0 : 1).update();
        if (rows == 0) {
            throw ApiException.notFound("HELP_ARTICLE_NOT_FOUND", "That answer doesn't exist any more");
        }
    }

    // ------------------------------------------------------------------ admin

    @Transactional(readOnly = true)
    public List<AdminHelpArticleDto> all() {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_HELP_ALL)).param("order", ORDER)
            .query(AdminRow.class).list().stream().map(AdminRow::toDto).toList();
    }

    @Transactional
    public AdminHelpArticleDto create(UUID adminId, HelpArticleRequest req) {
        long id;
        try {
            id = bind(jdbc.sql(QueryUtil.get(QueryConstants.INSERT_HELP_ARTICLE)), adminId, req).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw slugTaken(req.slug());
        }
        log.info("Help answer {} ({}) added by admin {}", id, req.slug(), adminId);
        return get(id);
    }

    @Transactional
    public AdminHelpArticleDto update(UUID adminId, long id, HelpArticleRequest req) {
        int rows;
        try {
            rows = bind(jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_HELP_ARTICLE)), adminId, req).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw slugTaken(req.slug());
        }
        if (rows == 0) {
            throw ApiException.notFound("HELP_ARTICLE_NOT_FOUND", "Answer not found");
        }
        log.info("Help answer {} ({}) edited by admin {}", id, req.slug(), adminId);
        return get(id);
    }

    @Transactional
    public void delete(UUID adminId, long id) {
        if (jdbc.sql(QueryUtil.get(QueryConstants.DELETE_HELP_ARTICLE)).param("id", id).update() == 0) {
            throw ApiException.notFound("HELP_ARTICLE_NOT_FOUND", "Answer not found");
        }
        log.info("Help answer {} deleted by admin {}", id, adminId);
    }

    private AdminHelpArticleDto get(long id) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_HELP_ARTICLE)).param("id", id).query(AdminRow.class).single().toDto();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, UUID adminId, HelpArticleRequest req) {
        String tags = req.ticketCategories() == null ? ""
            : req.ticketCategories().stream().distinct().map(Enum::name).collect(Collectors.joining(","));
        return spec.param("category", req.category().name()).param("slug", req.slug().trim())
            .param("question", req.question().trim()).param("answer", req.answer().trim()).param("tags", tags)
            .param("position", req.position()).param("published", req.published()).param("u", adminId);
    }

    private static ApiException slugTaken(String slug) {
        return ApiException.conflict("HELP_SLUG_TAKEN", "Another answer already uses the link name \"" + slug + "\"");
    }
}
