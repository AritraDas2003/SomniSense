package com.somnisense.backend.common.exception;

import com.somnisense.backend.common.response.ApiError;
import com.somnisense.backend.common.response.FieldErrorDetail;
import com.somnisense.backend.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns every exception thrown while handling a request into the standard ApiError JSON.
 *
 * How it works:
 * - @RestControllerAdvice: Spring applies the @ExceptionHandler methods below to ALL controllers.
 *   When a controller (or a service it calls) throws, Spring looks for the most specific matching
 *   handler and returns whatever it returns as the HTTP response.
 * - extends ResponseEntityExceptionHandler: Spring's base class already knows the correct status for
 *   its own MVC errors (405 wrong method, 415 wrong content type, 404 unknown URL, ...).
 *   We override handleExceptionInternal so ALL of them use our ApiError body.
 *
 * Safety rules: clients never see stack traces, SQL or internal class names. 5xx details are logged
 * server-side with the correlation ID so they can be found later.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------------------------------------------------------------- our own exceptions

    /** ResourceNotFoundException, ConflictException, InvalidRequestException and any other ApiException. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        return build(ex.getErrorCode(), ex.getMessage(), request, null);
    }

    // ---------------------------------------------------------------- validation

    /** @Valid on a @RequestBody failed, e.g. a blank fullName -> 400 with one entry per bad field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldErrorDetail> fields = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.add(new FieldErrorDetail(fe.getField(), fe.getDefaultMessage()));
        }
        ex.getBindingResult().getGlobalErrors().forEach(ge ->
                fields.add(new FieldErrorDetail(ge.getObjectName(), ge.getDefaultMessage())));
        return asObject(build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                servletRequest(request), fields));
    }

    /** Validation on @RequestParam / @PathVariable (e.g. @Min(1) on "size") failed. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldErrorDetail> fields = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(err ->
                    fields.add(new FieldErrorDetail(name, err.getDefaultMessage())));
        });
        return asObject(build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                servletRequest(request), fields));
    }

    /** Validation triggered inside a service annotated with @Validated. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                              HttpServletRequest request) {
        List<FieldErrorDetail> fields = new ArrayList<>();
        ex.getConstraintViolations().forEach(v ->
                fields.add(new FieldErrorDetail(v.getPropertyPath().toString(), v.getMessage())));
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(), request, fields);
    }

    /** Body missing, not JSON, or a value of the wrong type (e.g. "abc" for a number, unknown enum value). */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        // ex.getMessage() can contain Java class names, so we return our fixed message instead.
        return asObject(build(ErrorCode.MALFORMED_REQUEST, ErrorCode.MALFORMED_REQUEST.getDefaultMessage(),
                servletRequest(request), null));
    }

    // ---------------------------------------------------------------- database

    /**
     * A database constraint rejected the write (unique, foreign key, not-null).
     * Services should check these rules first and throw ConflictException with a clear message;
     * this handler is the safety net. The DB message contains SQL and data values, so it is NOT returned
     * and only logged at DEBUG level.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex,
                                                        HttpServletRequest request) {
        log.warn("Data integrity violation on {} ({})", request.getRequestURI(),
                ex.getMostSpecificCause().getClass().getSimpleName());
        log.debug("Data integrity violation detail", ex);
        return build(ErrorCode.DATA_INTEGRITY_VIOLATION, ErrorCode.DATA_INTEGRITY_VIOLATION.getDefaultMessage(),
                request, null);
    }

    // ---------------------------------------------------------------- security inside controllers/services

    /** Thrown by method-level checks such as @PreAuthorize("hasRole('ADMIN')") -> 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getDefaultMessage(), request, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return build(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getDefaultMessage(), request, null);
    }

    // ---------------------------------------------------------------- everything else

    /** Last resort: any unexpected bug -> 500 with a generic message; full stack trace goes to the log. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(), request, null);
    }

    /**
     * Every built-in Spring MVC error not overridden above ends up here (405, 415, 404 unknown URL,
     * missing request parameter, type mismatch, ...). We keep Spring's status and swap in our body.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = ErrorCode.fromStatus(statusCode.value());
        if (statusCode.is5xxServerError()) {
            log.error("Server error while handling request", ex);
        }
        ApiError error = new ApiError(code, code.getDefaultMessage(), servletRequest(request).getRequestURI(),
                CorrelationIdFilter.currentId(servletRequest(request)), null);
        // Keep Spring's headers (e.g. "Allow: GET" on a 405) but force our status to match the code.
        return ResponseEntity.status(code.getStatus()).headers(headers).body(error);
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<ApiError> build(ErrorCode code, String message, HttpServletRequest request,
                                           List<FieldErrorDetail> fieldErrors) {
        ApiError body = new ApiError(code, message, request.getRequestURI(),
                CorrelationIdFilter.currentId(request), fieldErrors);
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    private static HttpServletRequest servletRequest(WebRequest request) {
        return ((ServletWebRequest) request).getRequest();
    }

    /** The overridden base-class methods must return ResponseEntity<Object>; this just changes the generic type. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Object> asObject(ResponseEntity<ApiError> entity) {
        return (ResponseEntity) entity;
    }
}
