package com.somnisense.backend.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks that every kind of error comes back in the same ApiError JSON shape with the right status.
 *
 * - @SpringBootTest + @AutoConfigureMockMvc: start the whole app (with security) and send fake HTTP
 *   requests through MockMvc without opening a real port.
 * - @ActiveProfiles("test"): use somnisense_test_db.
 * - @Import(ErrorTestController.class): adds a controller that exists ONLY in this test.
 * - @WithMockUser: pretend a logged-in user sent the request (so we test the error, not the 401).
 * - .with(csrf()): adds a valid CSRF token to POST/DELETE test requests (required since Stage 3).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GlobalExceptionHandlerTest.ErrorTestController.class)
class GlobalExceptionHandlerTest {

    private static final String ISO_UTC = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$";

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ test-only controller

    record SampleRequest(@NotBlank String name, @Size(max = 5) String code) {
    }

    @RestController
    @RequestMapping("/test-errors")
    static class ErrorTestController {

        @PostMapping("/validate")
        String validate(@Valid @RequestBody SampleRequest body) {
            return "ok";
        }

        @GetMapping("/param")
        String param(@RequestParam @Min(1) int size) {
            return "ok";
        }

        @GetMapping("/not-found")
        String notFound() {
            throw new ResourceNotFoundException("Patient", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new ConflictException("Device already has an active assignment");
        }

        @GetMapping("/integrity")
        String integrity() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint users_email_key");
        }

        @GetMapping("/forbidden")
        String forbidden() {
            throw new AccessDeniedException("nope");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("SECRET internal detail: select * from users");
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    @WithMockUser
    void validationErrorListsEachBadField() throws Exception {
        mockMvc.perform(post("/test-errors/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"code\":\"TOO-LONG\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.path").value("/test-errors/validate"))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_UTC)))
                .andExpect(jsonPath("$.fieldErrors", hasSize(2)))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='name')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='code')]").exists())
                // the rejected value must never be echoed back
                .andExpect(content().string(not(containsString("TOO-LONG"))));
    }

    @Test
    @WithMockUser
    void invalidQueryParameterIsValidationError() throws Exception {
        mockMvc.perform(get("/test-errors/param").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }

    @Test
    @WithMockUser
    void wrongTypeQueryParameterIsBadRequest() throws Exception {
        mockMvc.perform(get("/test-errors/param").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser
    void malformedJsonIsBadRequestWithoutInternalDetails() throws Exception {
        mockMvc.perform(post("/test-errors/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(0)))
                .andExpect(content().string(not(containsString("jackson"))));
    }

    @Test
    @WithMockUser
    void notFoundExceptionGives404() throws Exception {
        mockMvc.perform(get("/test-errors/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Patient not found: 00000000-0000-0000-0000-000000000001"));
    }

    @Test
    @WithMockUser
    void conflictExceptionGives409() throws Exception {
        mockMvc.perform(get("/test-errors/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Device already has an active assignment"));
    }

    @Test
    @WithMockUser
    void databaseConstraintGives409WithoutSql() throws Exception {
        mockMvc.perform(get("/test-errors/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"))
                .andExpect(content().string(not(containsString("users_email_key"))));
    }

    @Test
    @WithMockUser
    void accessDeniedInsideControllerGives403() throws Exception {
        mockMvc.perform(get("/test-errors/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser
    void unexpectedExceptionGives500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/test-errors/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("SECRET"))));
    }

    @Test
    @WithMockUser
    void wrongHttpMethodGives405() throws Exception {
        mockMvc.perform(delete("/test-errors/conflict").with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @WithMockUser
    void wrongContentTypeGives415() throws Exception {
        mockMvc.perform(post("/test-errors/validate").with(csrf())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @WithMockUser
    void unknownUrlGives404() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void notLoggedInGives401InSameShape() throws Exception {
        mockMvc.perform(get("/test-errors/conflict"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/test-errors/conflict"))
                .andExpect(jsonPath("$.correlationId").exists())
                // no browser login popup
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void healthStaysPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
