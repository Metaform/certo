package org.metaform.certo.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public operational endpoints — {@code /health} (liveness), {@code /readiness} (liveness + DB), and
 * {@code /info} (descriptor). All must answer without a security token: they are not CCM protocol paths, so
 * container probes can reach them. The DB is up in the test context, so readiness is UP.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InfoControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void health_reportsUpWithoutAToken() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.application").value("Certo"))
                .andExpect(jsonPath("$.version").value("0.1.0"));
    }

    @Test
    void readiness_reportsUpWhenTheDatabaseIsReachable() throws Exception {
        mvc.perform(get("/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void info_returnsTheServiceDescriptorWithoutAToken() throws Exception {
        mvc.perform(get("/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Certo API"))
                .andExpect(jsonPath("$.description").isNotEmpty());
    }
}
