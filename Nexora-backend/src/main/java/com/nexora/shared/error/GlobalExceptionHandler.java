package com.nexora.shared.error;

import java.net.URI;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

// Extending ResponseEntityExceptionHandler keeps Spring MVC's own errors
// (404, 405, 400 bad JSON, ...) as proper ProblemDetail responses instead of
// letting the catch-all below turn them into 500s.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(BusinessException ex, HttpServletRequest request) {
        return build(ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockFailure(ObjectOptimisticLockingFailureException ex,
                                                     HttpServletRequest request) {
        return build(ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified by another user. Please reload and try again.", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error", ex);
        return build(ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Please try again later.", request);
    }

    private ProblemDetail build(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        pd.setProperty("code", code.name());
        pd.setProperty("timestamp", Instant.now());
        pd.setInstance(URI.create(request.getRequestURI()));
        return pd;
    }
}
