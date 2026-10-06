package com.nexora.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Base class for every test that needs the full application and a real database.
// Extend it:  class MyTest extends AbstractIntegrationTest { ... }
//
// A real PostgreSQL 17 runs in a throwaway Docker container (never H2 and never your dev
// database): the same engine, locks and constraints as production.
@SpringBootTest
public abstract class AbstractIntegrationTest {

    // static + started once = the "singleton container" pattern. All test classes share one
    // container (fast), and Spring can cache the application context between them.
    // Testcontainers' Ryuk helper container removes it when the JVM exits.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    // Runs before the application context starts. It points spring.datasource.* at the
    // container (random host port), overriding application.yml. Flyway then migrates the empty
    // database on startup, so the tests also prove the migrations work from scratch.
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
