package com.syncspace.presence;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic safety net for TTL expiry: if a client vanished without a
 * disconnect event reaching the server, its session keys still expire
 * on their own, and this sweep — every
 * {@code syncspace.presence.sweep-interval-ms} (15 s by default) —
 * notices the users whose last broadcast state is online but who hold
 * no live key, and broadcasts them offline. See
 * {@link PresenceService} for the mechanism and the key layout.
 */
@Component
public class PresenceSweeper {

    private final PresenceService presenceService;

    public PresenceSweeper(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @Scheduled(fixedDelayString = "${syncspace.presence.sweep-interval-ms:15000}")
    public void sweep() {
        presenceService.sweepExpired();
    }
}
