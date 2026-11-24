package com.syncspace.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The requested resource does not exist, or the caller is not a member of it.
 * Membership failures use 404 deliberately so workspace existence is not
 * leaked to non-members.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
