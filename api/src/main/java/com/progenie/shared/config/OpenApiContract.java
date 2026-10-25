package com.progenie.shared.config;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Makes /v3/api-docs say exactly which fields can be missing or null, so the generated Angular client
 * (web/src/app/core/api/generated) has precise types instead of everything optional:
 * <ul>
 *   <li>Responses: every field is always present (Jackson writes nulls), so all are {@code required}; a field
 *       is {@code null}-able only when its type is annotated {@code @Nullable}.</li>
 *   <li>Request bodies: a field is required when it is primitive or has @NotNull, @NotBlank or @NotEmpty;
 *       every other field may be left out or sent as null.</li>
 * </ul>
 * Nullable uses JSpecify's {@link Nullable} (already on the classpath through Spring Framework 7), written on the
 * record component's type: {@code @Nullable String line2}.
 */
@Component
public class OpenApiContract implements OpenApiCustomizer {

    private static final Logger log = LoggerFactory.getLogger(OpenApiContract.class);

    private final Map<String, Class<?>> records = scanRecords();

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Set<String> requestBodies = requestBodySchemas(openApi);
        openApi.getComponents().getSchemas().forEach((name, schema) -> {
            Class<?> type = recordFor(name);
            Map<String, Schema> properties = schema.getProperties();
            if (type == null || properties == null) {
                return;
            }
            boolean request = requestBodies.contains(name);
            List<String> required = new ArrayList<>();
            for (RecordComponent component : type.getRecordComponents()) {
                Schema property = properties.get(component.getName());
                if (property == null) {
                    continue; // renamed or ignored by Jackson
                }
                boolean present = !request || component.getType().isPrimitive() || mustBePresent(component);
                boolean nullable = component.getAnnotatedType().isAnnotationPresent(Nullable.class)
                    || (request && !present);
                if (nullable) {
                    properties.put(component.getName(), nullable(property));
                }
                if (present) {
                    required.add(component.getName());
                }
            }
            schema.setRequired(required.isEmpty() ? null : required);
        });
    }

    /**
     * Schemas only ever sent in request bodies (directly or nested). A record that also appears in a response
     * (e.g. preferences: read and written back) follows the response rules.
     */
    @SuppressWarnings("rawtypes")
    private static Set<String> requestBodySchemas(OpenAPI openApi) {
        java.util.List<Schema> requests = new ArrayList<>();
        java.util.List<Schema> responses = new ArrayList<>();
        if (openApi.getPaths() != null) {
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(op -> {
                if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
                    op.getRequestBody().getContent().values().forEach(m -> requests.add(m.getSchema()));
                }
                if (op.getResponses() != null) {
                    op.getResponses().values().forEach(r -> {
                        if (r.getContent() != null) {
                            r.getContent().values().forEach(m -> responses.add(m.getSchema()));
                        }
                    });
                }
            }));
        }
        Set<String> out = reachable(openApi, requests);
        out.removeAll(reachable(openApi, responses));
        return out;
    }

    /** Names of the component schemas reachable from the given schemas ($ref, properties, items, anyOf). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Set<String> reachable(OpenAPI openApi, java.util.List<Schema> roots) {
        Set<String> names = new java.util.HashSet<>();
        Map<String, Schema> all = openApi.getComponents().getSchemas();
        java.util.Deque<Schema> todo = new java.util.ArrayDeque<>();
        roots.stream().filter(java.util.Objects::nonNull).forEach(todo::add);
        while (!todo.isEmpty()) {
            Schema s = todo.pop();
            if (s.get$ref() != null) {
                String name = s.get$ref().substring(s.get$ref().lastIndexOf('/') + 1);
                if (names.add(name) && all.get(name) != null) {
                    todo.add(all.get(name));
                }
            }
            if (s.getProperties() != null) {
                s.getProperties().values().forEach(p -> todo.add((Schema) p));
            }
            if (s.getItems() != null) {
                todo.add(s.getItems());
            }
            if (s.getAnyOf() != null) {
                s.getAnyOf().forEach(p -> todo.add((Schema) p));
            }
        }
        return names;
    }

    /** The record behind a schema name; generic records get names like PageResponseBookingSummaryDto. */
    private Class<?> recordFor(String schemaName) {
        Class<?> exact = records.get(schemaName);
        if (exact != null) {
            return exact;
        }
        return records.entrySet().stream()
            .filter(e -> e.getValue().getTypeParameters().length > 0 && schemaName.startsWith(e.getKey()))
            .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /** Bean Validation annotations land on the accessor and the field, not on the record component itself. */
    private static boolean mustBePresent(RecordComponent c) {
        for (Class<? extends java.lang.annotation.Annotation> a : List.of(NotNull.class, NotBlank.class, NotEmpty.class)) {
            if (c.getAccessor().isAnnotationPresent(a) || c.getAnnotatedType().isAnnotationPresent(a)) {
                return true;
            }
        }
        return false;
    }

    /** OpenAPI 3.1: add "null" to the type, or anyOf [$ref, null] for a reference. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullable(Schema property) {
        if (property.get$ref() != null) {
            Schema wrapper = new Schema<>();
            wrapper.setDescription(property.getDescription());
            wrapper.setAnyOf(List.of(new Schema<>().$ref(property.get$ref()), new Schema<>().types(Set.of("null"))));
            return wrapper;
        }
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) {
            types.addAll(property.getTypes());
        } else if (property.getType() != null) {
            types.add(property.getType());
        }
        types.add("null");
        property.setTypes(types);
        return property;
    }

    /** Records under com.progenie by simple name (springdoc names schemas by simple class name). */
    private static Map<String, Class<?>> scanRecords() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Record.class));
        Map<String, Class<?>> out = new HashMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.progenie")) {
            try {
                Class<?> type = Class.forName(bd.getBeanClassName(), false, OpenApiContract.class.getClassLoader());
                if (type.isLocalClass() || type.isAnonymousClass()) {
                    continue; // records declared inside a method are never part of the API
                }
                Class<?> previous = out.putIfAbsent(type.getSimpleName(), type);
                if (previous != null && previous != type) {
                    log.warn("Two records named {} ({} and {}): the OpenAPI schema can only describe one", type.getSimpleName(),
                        previous.getName(), type.getName());
                }
            } catch (ClassNotFoundException | LinkageError e) {
                log.debug("Skipping {}: {}", bd.getBeanClassName(), e.getMessage());
            }
        }
        return out;
    }
}
