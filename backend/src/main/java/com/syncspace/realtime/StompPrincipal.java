package com.syncspace.realtime;

import java.security.Principal;
import java.util.UUID;

/**
 * The authenticated principal bound to a STOMP session by
 * {@link StompAuthChannelInterceptor}. The name is the user id, so
 * {@code /user} destinations resolve per user.
 */
public record StompPrincipal(UUID userId) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
