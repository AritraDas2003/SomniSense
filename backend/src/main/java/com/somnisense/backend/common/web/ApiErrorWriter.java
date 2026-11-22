package com.somnisense.backend.common.web;

import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.common.response.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes an ApiError straight to the HTTP response.
 *
 * Why is this needed? Spring Security rejects unauthenticated/forbidden requests in a FILTER, before any
 * controller runs, so GlobalExceptionHandler (@RestControllerAdvice) never sees those errors.
 * The security handlers use this class so 401/403 bodies look exactly like every other error.
 *
 * JsonMapper is Jackson 3's JSON converter; Spring Boot creates one bean and we receive it through
 * the constructor ("constructor injection" - Spring passes it in automatically).
 */
@Component
public class ApiErrorWriter {

    private final JsonMapper jsonMapper;

    public ApiErrorWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode)
            throws IOException {
        ApiError body = new ApiError(errorCode, errorCode.getDefaultMessage(), request.getRequestURI(),
                CorrelationIdFilter.currentId(request), null);
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
