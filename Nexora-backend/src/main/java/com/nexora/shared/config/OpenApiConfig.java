package com.nexora.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

// Gives the generated API documentation its header (title, version, description).
// springdoc finds this bean automatically. Whether the docs are served at all is decided
// by the springdoc.* switches in application.yml (off) and application-dev.yml (on).
// Task 1.4 will add the JWT security scheme here so Swagger UI gets an "Authorize" button.
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI nexoraOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SmartSilk (Nexora) API")
                .version("v1")
                .description("Business visibility system for silk-yarn trading: purchases, "
                        + "inventory, orders, payments and cash position."));
    }
}
