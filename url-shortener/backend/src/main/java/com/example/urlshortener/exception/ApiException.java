package com.example.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Application error that carries the HTTP status the client should see. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException gone(String message) {
        return new ApiException(HttpStatus.GONE, message);
    }

    public static ApiException serverError(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
