package com.progenie.shared.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.progenie.shared.config.QueryConstants;

class QueryUtilTest {

    private static Map<String, String> constants() {
        return Arrays.stream(QueryConstants.class.getDeclaredFields())
            .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
            .collect(Collectors.toMap(Field::getName, f -> {
                try {
                    return (String) f.get(null);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }));
    }

    @Test
    void everyConstantHasAStatementAndItsNameMatchesTheKey() {
        Set<String> keys = QueryUtil.keys();
        constants().forEach((name, key) -> {
            assertThat(keys).as("sql_scripts.properties has no entry for QueryConstants.%s", name).contains(key);
            assertThat(name).as("constant name for %s", key).isEqualTo(key.replaceAll("[.-]", "_").toUpperCase());
        });
    }

    @Test
    void everyStatementHasAConstant() {
        Set<String> used = new HashSet<>(constants().values());
        List<String> orphans = QueryUtil.keys().stream()
            .filter(k -> !k.startsWith(QueryUtil.FRAGMENT_PREFIX) && !used.contains(k))
            .sorted().toList();
        assertThat(orphans).as("entries in sql_scripts.properties without a constant in QueryConstants").isEmpty();
    }

    @Test
    void noConstantIsDeclaredTwice() {
        List<String> values = List.copyOf(constants().values());
        assertThat(new HashSet<>(values)).hasSameSizeAs(values);
    }

    @Test
    void everyIncludeResolves() {
        QueryUtil.load(QueryUtil.FILE).forEach((key, sql) -> assertThat(sql).as(key).doesNotContain("${"));
    }

    @Test
    void includesAndSlotsWork() {
        Map<String, String> q = QueryUtil.load("query_util_test.properties");
        assertThat(q.get("get.thing.one")).isEqualTo("SELECT id FROM things WHERE id = :id");
        assertThat(q.get("get.thing.sorted")).isEqualTo("SELECT id FROM things ORDER BY {{orderBy}}");
    }

    @Test
    void realSlotsAreFilledOnlyWithOtherKeys() {
        String sql = QueryUtil.get(QueryConstants.GET_GENIE_LIST, Map.of(
            "categoryFilter", QueryConstants.FRAGMENT_GENIE_FILTER_CATEGORY,
            "serviceFilter", QueryConstants.FRAGMENT_NONE,
            "orderBy", QueryConstants.FRAGMENT_GENIE_ORDER_PRICE));
        assertThat(sql).contains("AND c.slug = :category").contains("ORDER BY starting_price ASC").doesNotContain("{{");

        assertThatThrownBy(() -> QueryUtil.get(QueryConstants.GET_GENIE_LIST))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("slot");
        assertThatThrownBy(() -> QueryUtil.get(QueryConstants.GET_GENIE_LIST, Map.of(
            "categoryFilter", "AND 1=1; DROP TABLE users", "serviceFilter", QueryConstants.FRAGMENT_NONE,
            "orderBy", QueryConstants.FRAGMENT_GENIE_ORDER_PRICE)))
            .as("a slot value must be a key, never SQL").isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No SQL under the key");
        assertThatThrownBy(() -> QueryUtil.get(QueryConstants.GET_GENIE_LIST, Map.of("unknownSlot", QueryConstants.FRAGMENT_NONE)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownKeysAndCircularIncludesFailWithAClearMessage() {
        assertThatThrownBy(() -> QueryUtil.get("get.nothing.here"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("get.nothing.here");
        assertThatThrownBy(() -> QueryUtil.load("query_util_cycle.properties"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Circular");
    }
}
