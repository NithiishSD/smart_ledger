package com.nexora;

import org.junit.jupiter.api.Test;

import com.nexora.support.AbstractIntegrationTest;

// Extends AbstractIntegrationTest: the context now loads against a Testcontainers PostgreSQL
// (and runs every Flyway migration on an empty database) instead of your local dev database.
class NexoraApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}
