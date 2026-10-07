package com.nexora.shared.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.nexora.support.AbstractIntegrationTest;

// Same application, but with the prod profile: the API docs must NOT be served.
// A different profile means Spring builds a separate application context for this class.
// (The datasource comes from AbstractIntegrationTest's dynamic properties, so the prod
// file's required DB_URL placeholder is never needed here.)
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class OpenApiProdProfileTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void apiDocs_inProdProfile_areNotExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    }

    @Test
    void swaggerUi_inProdProfile_isNotExposed() throws Exception {
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    }
}
