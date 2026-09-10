package com.akshitanchan.saas.web;

import org.springframework.http.HttpStatus;

// thrown by service/controller code for a known, user-facing failure; the advice
// turns it into {"detail": ...} with the given status, mirroring fastapi's HTTPException
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String detail;

    public ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
        this.detail = detail;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }
}
