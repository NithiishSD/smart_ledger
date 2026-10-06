package com.nexora.shared.error;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

// @RestControllerAdvice = one class that handles exceptions for ALL controllers.
//   Every return value is written as JSON (the "Rest" part).
// "extends ResponseEntityExceptionHandler" keeps Spring MVC's own errors
//   (404 unknown URL, 405 wrong method, 400 bad JSON ...) as proper ProblemDetail
//   responses. Without it, the catch-all handler below would turn them into 500s.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // SLF4J logger: writes to the application log. static final = one shared instance.
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // The time source. final = it can never be reassigned after construction.
    private final Clock clock;

    // Constructor injection: Spring sees the Clock parameter and passes in the bean
    // from ClockConfig. A unit test can call "new GlobalExceptionHandler(fixedClock)"
    // with a fake clock, which is not possible with field @Autowired.
    // NOTE: the Clock must be java.time.Clock. io.micrometer's Clock is a different class
    // (used by metrics) and would give the wrong type here.
    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    // Runs whenever any controller/service throws a BusinessException (or a subclass).
    // Spring supplies "ex" (the thrown exception) and "request" (the current HTTP request).
    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(BusinessException ex, HttpServletRequest request) {
        // The ErrorCode inside the exception decides the HTTP status and the "code" field.
        return build(ex.getCode(), ex.getMessage(), request);
    }

    // Hibernate throws this when the @Version check fails: someone else saved the
    // same row first (optimistic locking). We translate it into a 409 conflict.
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockFailure(ObjectOptimisticLockingFailureException ex,
                                                     HttpServletRequest request) {
        return build(ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified by another user. Please reload and try again.", request);
    }

    // Catch-all for anything unexpected. Spring always picks the MOST SPECIFIC handler,
    // so this only runs when no handler above (or in the parent class) matches.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex, HttpServletRequest request) {
        // Full details go to the LOG only, with the stack trace, so developers can debug.
        log.error("Unexpected error", ex);
        // The client gets a generic message: never leak ex.getMessage() (SQL, paths, class names).
        return build(ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Please try again later.", request);
    }

    // One place that builds the response body, so every error has the same shape.
    private ProblemDetail build(ErrorCode code, String detail, HttpServletRequest request) {
        // Standard RFC 9457 body: sets "status", "title" (from the status) and "detail".
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        // Our stable machine-readable code, e.g. "INSUFFICIENT_INVENTORY".
        pd.setProperty("code", code.name());
        // "now" according to the injected clock, written as an ISO-8601 string.
        // (Passing the Clock object itself, as before, would serialise the clock, not the time.)
        pd.setProperty("timestamp", Instant.now(clock));
        // "instance" = the request path that failed, e.g. /api/v1/demo/error.
        pd.setInstance(URI.create(request.getRequestURI()));
        return pd;
    }
}
