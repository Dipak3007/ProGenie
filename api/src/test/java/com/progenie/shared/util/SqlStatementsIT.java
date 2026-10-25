package com.progenie.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.progenie.it.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;

/**
 * Every statement in sql_scripts.properties is checked by PostgreSQL itself against the migrated schema:
 * it is prepared on the server (parse + describe), which catches syntax errors, unknown tables or columns,
 * wrong types and ambiguous functions, without running it. Unit tests can't catch these.
 *
 * <p>Statements with {{slots}} are tried with the fragments of their own area (fragment.genie.* for
 * get.genie.list, ...) until one combination is accepted. The JPQL at the end of the file (used by Spring Data
 * repositories) is checked by Hibernate against the entities instead.
 */
class SqlStatementsIT extends IntegrationTest {

    private static final Pattern SLOT = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");
    private static final int MAX_COMBINATIONS = 5000;

    @Autowired
    DataSource dataSource;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void everyStatementIsValidAgainstTheRealSchema() throws Exception {
        Map<String, String> all = QueryUtil.load(QueryUtil.FILE);
        Set<String> jpql = jpqlKeys();
        Map<String, String> failures = new TreeMap<>();
        int checked = 0;
        try (Connection c = dataSource.getConnection()) {
            for (var entry : all.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith(QueryUtil.FRAGMENT_PREFIX)) {
                    continue; // checked as part of the statements that include them
                }
                checked++;
                String error = jpql.contains(key) ? checkJpql(entry.getValue()) : check(c, key, entry.getValue(), all);
                if (error != null) {
                    failures.put(key, error);
                }
            }
        }
        assertThat(checked).isGreaterThan(300);
        assertThat(failures).as("statements PostgreSQL rejects").isEmpty();
    }

    /** Keys below the "JPQL for Spring Data repositories" banner in sql_scripts.properties. */
    private static Set<String> jpqlKeys() throws java.io.IOException {
        Set<String> keys = new HashSet<>();
        try (var in = SqlStatementsIT.class.getClassLoader().getResourceAsStream(QueryUtil.FILE)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int banner = text.indexOf("JPQL for Spring Data");
            assertThat(banner).as("JPQL section banner").isPositive();
            for (String line : text.substring(banner).split("\\R")) {
                int eq = line.indexOf('=');
                if (!line.startsWith("#") && eq > 0) {
                    keys.add(line.substring(0, eq).trim());
                }
            }
        }
        return keys;
    }

    private String checkJpql(String jpql) {
        try {
            entityManager.createQuery(jpql);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /** Null when the statement (with some valid slot combination) is accepted, else the last error. */
    private String check(Connection c, String key, String sql, Map<String, String> all) {
        List<String> slots = new ArrayList<>();
        Matcher m = SLOT.matcher(sql);
        while (m.find()) {
            if (!slots.contains(m.group(1))) {
                slots.add(m.group(1));
            }
        }
        if (slots.isEmpty()) {
            return prepare(c, sql);
        }
        String area = key.split("\\.")[1]; // get.genie.list -> genie
        List<String> candidates = new ArrayList<>();
        candidates.add("");                                        // fragment.none
        all.forEach((k, v) -> {
            if (k.startsWith(QueryUtil.FRAGMENT_PREFIX + area + ".")) {
                candidates.add(v);
            }
        });
        String last = "no combination tried";
        int[] index = new int[slots.size()];
        for (int tried = 0; tried < MAX_COMBINATIONS; tried++) {
            Map<String, String> fill = new LinkedHashMap<>();
            for (int i = 0; i < slots.size(); i++) {
                fill.put(slots.get(i), candidates.get(index[i]));
            }
            String filled = sql;
            for (var f : fill.entrySet()) {
                filled = filled.replace("{{" + f.getKey() + "}}", f.getValue());
            }
            last = prepare(c, filled);
            if (last == null) {
                return null;
            }
            // next combination (odometer)
            int i = 0;
            while (i < index.length && ++index[i] == candidates.size()) {
                index[i++] = 0;
            }
            if (i == index.length) {
                break;
            }
        }
        return last;
    }

    private static String prepare(Connection c, String sql) {
        String jdbcSql = NamedParameterUtils.parseSqlStatementIntoString(sql);
        try (PreparedStatement ps = c.prepareStatement(jdbcSql)) {
            ps.getParameterMetaData(); // makes pgjdbc send Parse + Describe to the server now
            return null;
        } catch (SQLException e) {
            return e.getMessage();
        }
    }
}
