package com.syncspace.presence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.UUID;

/**
 * Fast path for going offline: when a STOMP session ends — the client
 * sent DISCONNECT, the transport closed, or the server dropped the
 * session after missed STOMP heartbeats — Spring publishes a
 * {@code SessionDisconnectEvent} and this listener removes that
 * session's presence keys. If the user has no other live session in a
 * workspace, they are broadcast offline immediately. Sessions that
 * vanish without any event reaching the server (crashed client, killed
 * network) are covered instead by the TTL-expiry sweep in
 * {@link PresenceSweeper} — see {@link PresenceService} for the full
 * picture.
 */
@Component
public class PresenceDisconnectListener implements ApplicationListener<SessionDisconnectEvent> {

    private static final Logger log = LoggerFactory.getLogger(PresenceDisconnectListener.class);

    private final PresenceService presenceService;

    public PresenceDisconnectListener(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @Override
    public void onApplicationEvent(SessionDisconnectEvent event) {
        Principal principal = event.getUser();
        if (principal == null) {
            return; // never authenticated; nothing to clean up
        }
        UUID userId;
        try {
            userId = UUID.fromString(principal.getName());
        } catch (IllegalArgumentException ex) {
            log.warn("Ignoring disconnect for non-UUID principal {}", principal.getName());
            return;
        }
        presenceService.handleDisconnect(event.getSessionId(), userId);
    }
}
