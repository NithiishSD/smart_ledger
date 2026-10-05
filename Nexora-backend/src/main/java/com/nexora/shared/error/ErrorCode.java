package com.nexora.shared.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    
    
    
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),

    // 401 Unauthorized: we don't know who you are
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),

    // 403 Forbidden: we know who you are, you may not do this
    ACCESS_DENIED(HttpStatus.FORBIDDEN),

    // 404 Not Found
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),

    // 409 Conflict: clashes with the current state
    DUPLICATE_BUSINESS_NUMBER(HttpStatus.CONFLICT),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    ORDER_LOCKED(HttpStatus.CONFLICT),
    INSUFFICIENT_INVENTORY(HttpStatus.CONFLICT),
    INSUFFICIENT_FUNDS(HttpStatus.CONFLICT),
    PURCHASE_RECEIPT_EXCEEDED(HttpStatus.CONFLICT),
    OUTSOURCE_RECEIPT_EXCEEDED(HttpStatus.CONFLICT),
    PAYMENT_ALLOCATION_EXCEEDED(HttpStatus.CONFLICT),

    // 422 Unprocessable: well-formed, but never valid as asked
    INVALID_PAYMENT_ALLOCATION(HttpStatus.UNPROCESSABLE_CONTENT),
    PRODUCTION_NOT_RECONCILED(HttpStatus.UNPROCESSABLE_CONTENT),
    INVALID_PARTY_ROLE(HttpStatus.UNPROCESSABLE_CONTENT),
    PARTY_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
    INVALID_PRODUCT_FOR_OPERATION(HttpStatus.UNPROCESSABLE_CONTENT),
    INVALID_PAYMENT_METHOD(HttpStatus.UNPROCESSABLE_CONTENT),

    // 500
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) { this.status = status; }

    public HttpStatus status() { return status; }
}


