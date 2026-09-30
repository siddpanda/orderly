package com.example.orderly.dto;

import java.time.Instant;

/** Consistent error envelope returned by {@link com.example.orderly.exception.GlobalExceptionHandler}. */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
