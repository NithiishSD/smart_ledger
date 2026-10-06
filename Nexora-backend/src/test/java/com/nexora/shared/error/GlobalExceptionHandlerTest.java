package com.nexora.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

// Unit test with NO Spring context: this is the payoff of constructor injection.
// We create the handler ourselves and hand it a fixed (fake) clock.
class GlobalExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void handleBusiness_buildsProblemDetailWithCodeStatusAndTimestamp() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/demo/error");
        BusinessException ex =
                new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY, "Only 42.500 kg available.");

        ProblemDetail pd = handler.handleBusiness(ex, request);

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getDetail()).isEqualTo("Only 42.500 kg available.");
        assertThat(pd.getInstance()).hasToString("/api/v1/demo/error");
        assertThat(pd.getProperties())
                .containsEntry("code", "INSUFFICIENT_INVENTORY")
                .containsEntry("timestamp", NOW);   // proves the injected clock is used
    }

    @Test
    void handleGeneric_doesNotLeakExceptionMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");

        ProblemDetail pd = handler.handleGeneric(new RuntimeException("SELECT * FROM secret_table"), request);

        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getDetail()).doesNotContain("secret_table");
        assertThat(pd.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }
}
