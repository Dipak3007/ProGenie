package com.progenie.shared.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.progenie.shared.config.QueryConstants;

/**
 * The only way the API gets SQL: every statement lives in {@code src/main/resources/sql_scripts.properties} under a
 * key, and code refers to the key through a constant in {@link QueryConstants}.
 *
 * <pre>{@code
 * jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_LIST)).param(...)
 * }</pre>
 *
 * <p>Two conventions keep the file readable and safe:
 * <ul>
 *   <li><b>{@code ${other.key}}</b> includes another entry (e.g. a shared SELECT list). Includes are resolved once, at
 *       load time, so every key maps to one complete statement.</li>
 *   <li><b>{@code {{slot}}}</b> marks the few places whose shape changes at runtime: a sort order, an optional filter,
 *       a row lock. {@link #get(String, Map)} fills each slot with <em>another key</em> from the file, never with a raw
 *       string, so user input can't reach the SQL text. Values still travel as bind parameters ({@code :name}).</li>
 * </ul>
 *
 * <p>The file is read once, the first time a query is needed, and a missing key fails immediately with its name.
 * {@code QueryUtilTest} also checks at build time that every constant has an entry and every entry has a constant.
 */
public final class QueryUtil {

    /** Classpath location, also used by Spring Data for the repositories' named queries (see {@code JpaConfig}). */
    public static final String FILE = "sql_scripts.properties";

    /** Keys that only exist to be included or used as a slot value, and so need no constant of their own. */
    public static final String FRAGMENT_PREFIX = "fragment.";

    private static final Pattern INCLUDE = Pattern.compile("\\$\\{([A-Za-z0-9._-]+)}");
    private static final Pattern SLOT = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");

    private QueryUtil() {
    }

    /** The statement stored under {@code key}. */
    public static String get(String key) {
        String sql = lookup(key);
        Matcher slot = SLOT.matcher(sql);
        if (slot.find()) {
            throw new IllegalArgumentException("SQL '" + key + "' has the slot {{" + slot.group(1)
                    + "}}: use QueryUtil.get(key, slots)");
        }
        return sql;
    }

    /**
     * The statement stored under {@code key}, with each {@code {{slot}}} replaced by the statement stored under the key
     * given for it, e.g. {@code get(LIST_BOOKINGS, Map.of("orderBy", FRAGMENT_ORDER_SOONEST))}.
     */
    public static String get(String key, Map<String, String> slotKeys) {
        String sql = lookup(key);
        Matcher m = SLOT.matcher(sql);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String slot = m.group(1);
            String fragmentKey = slotKeys.get(slot);
            if (fragmentKey == null) {
                throw new IllegalArgumentException("SQL '" + key + "' needs a key for the slot {{" + slot + "}}");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(lookup(fragmentKey)));
        }
        m.appendTail(out);
        for (String slot : slotKeys.keySet()) {
            if (!sql.contains("{{" + slot + "}}")) {
                throw new IllegalArgumentException("SQL '" + key + "' has no slot {{" + slot + "}}");
            }
        }
        return out.toString();
    }

    /** Every key in the file (for the consistency test). */
    public static Set<String> keys() {
        return Holder.QUERIES.keySet();
    }

    private static String lookup(String key) {
        String sql = Holder.QUERIES.get(key);
        if (sql == null) {
            throw new IllegalArgumentException("No SQL under the key '" + key + "' in " + FILE);
        }
        return sql;
    }

    /** Loaded on first use (class initialisation is thread-safe and happens once). */
    private static final class Holder {
        private static final Map<String, String> QUERIES = load(FILE);
    }

    static Map<String, String> load(String resource) {
        Properties raw = new Properties();
        try (InputStream in = QueryUtil.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not found on the classpath");
            }
            raw.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
        Map<String, String> source = new TreeMap<>();
        raw.stringPropertyNames().forEach(k -> source.put(k, raw.getProperty(k)));

        Map<String, String> resolved = new LinkedHashMap<>();
        for (String key : source.keySet()) {
            resolve(key, source, resolved, new ArrayDeque<>());
        }
        return Collections.unmodifiableMap(resolved);
    }

    private static String resolve(String key, Map<String, String> source, Map<String, String> done, Deque<String> path) {
        String cached = done.get(key);
        if (cached != null) {
            return cached;
        }
        if (path.contains(key)) {
            throw new IllegalStateException("Circular ${...} include in " + FILE + ": " + String.join(" -> ", path) + " -> " + key);
        }
        String text = source.get(key);
        if (text == null) {
            throw new IllegalStateException(FILE + ": '" + path.peekLast() + "' includes the unknown key '" + key + "'");
        }
        path.addLast(key);
        Matcher m = INCLUDE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(resolve(m.group(1), source, done, path)));
        }
        m.appendTail(out);
        path.removeLast();
        String sql = out.toString().strip();
        done.put(key, sql);
        return sql;
    }
}
