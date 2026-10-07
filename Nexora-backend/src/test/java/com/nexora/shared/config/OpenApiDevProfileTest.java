package com.nexora.shared.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.nexora.support.AbstractIntegrationTest;

// No profile is set, so the default profile "dev" is active (spring.profiles.default in
// application.yml) and the API docs are switched on.
// @AutoConfigureMockMvc gives us a MockMvc: it calls controllers in-process, no real HTTP port.
@AutoConfigureMockMvc
class OpenApiDevProfileTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void apiDocs_inDevProfile_returnsOurTitle() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("SmartSilk (Nexora) API"));
    }
}
