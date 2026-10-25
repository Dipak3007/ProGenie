package com.progenie.shared.config;

import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

/**
 * Stable, unique operation ids: controller name + method name, e.g. CatalogController.cities -> catalogCities and
 * AdminCatalogController.cities -> adminCatalogCities. springdoc's default (the method name, with _1, _2 added on
 * clashes) depends on scan order, so the generated client's function names could change between builds.
 */
@Component
public class OpenApiOperationIds implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handler) {
        String controller = handler.getBeanType().getSimpleName().replaceFirst("Controller$", "");
        String method = handler.getMethod().getName();
        operation.setOperationId(Character.toLowerCase(controller.charAt(0)) + controller.substring(1)
            + Character.toUpperCase(method.charAt(0)) + method.substring(1));
        return operation;
    }
}
