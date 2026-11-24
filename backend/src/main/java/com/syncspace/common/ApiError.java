package com.syncspace.common;

import java.util.Map;

/**
 * Standard error envelope for all API error responses.
 * Example: { "error": { "code": "VALIDATION_FAILED", "message": "…" } }
 */
public record ApiError(String code, String message, Map<String, String> details) {

    public ApiError(String code, String message) {
        this(code, message, null);
    }
}
