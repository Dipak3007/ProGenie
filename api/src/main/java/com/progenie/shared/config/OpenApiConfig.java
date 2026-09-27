package com.progenie.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document served at /v3/api-docs; the Angular API client is generated from it. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI proGenieOpenApi() {
        return new OpenAPI()
            .info(new Info().title("ProGenie API").version("v1")
                .description("Home-services marketplace: catalog, Genies, bookings, pricing"))
            .components(new Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
