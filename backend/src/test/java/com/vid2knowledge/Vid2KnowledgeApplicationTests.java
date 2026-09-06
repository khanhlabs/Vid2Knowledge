package com.vid2knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "gemini.api-key=test-key",
        "features.persistence-enabled=false"
})
@AutoConfigureMockMvc
@Import(Vid2KnowledgeApplicationTests.TestEndpointsConfiguration.class)
class Vid2KnowledgeApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void returnsStandardNotFoundError() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/does-not-exist"));
    }

    @Test
    void returnsStandardValidationError() throws Exception {
        mockMvc.perform(post("/test/errors/validate")
                        .contentType("application/json")
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("value"))
                .andExpect(jsonPath("$.path").value("/test/errors/validate"));
    }

    @Test
    void returnsStandardRejectedRequestError() throws Exception {
        mockMvc.perform(get("/test/errors/rejected"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
                .andExpect(jsonPath("$.message").value("Quota exceeded"));
    }

    @Test
    void returnsSafeUnexpectedError() throws Exception {
        mockMvc.perform(get("/test/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(not(containsString("secret"))));
    }

    @Test
    void allowsCorsPreflightFromLocalFrontend() throws Exception {
        mockMvc.perform(options("/actuator/health")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void propagatesSafeCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist")
                        .header("X-Correlation-ID", "request-123"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-ID", "request-123"))
                .andExpect(jsonPath("$.correlationId").value("request-123"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEndpointsConfiguration {

        @Bean
        TestErrorController testErrorController() {
            return new TestErrorController();
        }
    }

    @RestController
    @RequestMapping("/test/errors")
    static class TestErrorController {

        @PostMapping("/validate")
        ResponseEntity<Void> validate(@Valid @RequestBody TestRequest request) {
            return ResponseEntity.noContent().build();
        }

        @RequestMapping("/rejected")
        ResponseEntity<Void> rejected() {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Quota exceeded");
        }

        @RequestMapping("/unexpected")
        ResponseEntity<Void> unexpected() {
            throw new IllegalStateException("secret must not reach the client");
        }
    }

    record TestRequest(@NotBlank String value) {
    }

}
