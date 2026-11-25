package com.syncspace.realtime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The event envelope every STOMP message body uses
 * (see docs/websocket-events.md). Clients deduplicate on {@code eventId}
 * and must tolerate duplicate deliveries; exactly-once is never claimed.
 *
 * @param eventId     unique per event
 * @param eventType   one of the types in docs/websocket-events.md
 * @param workspaceId the workspace the event belongs to (null for user-targeted events)
 * @param resourceId  the document / message / task / notification id
 * @param actorId     the user who caused the event
 * @param timestamp   when the event was created
 * @param version     the resource version after the change (1 when the resource carries no version)
 * @param payload     event-specific data
 */
public record RealtimeEvent(
        String eventId,
        String eventType,
        UUID workspaceId,
        UUID resourceId,
        UUID actorId,
        Instant timestamp,
        long version,
        Map<String, Object> payload) {

    public static RealtimeEvent create(
            String eventType, UUID workspaceId, UUID resourceId,
            UUID actorId, long version, Map<String, Object> payload) {
        return new RealtimeEvent(
                UUID.randomUUID().toString(), eventType, workspaceId,
                resourceId, actorId, Instant.now(), version, payload);
    }

    /** Error envelope sent to {@code /user/queue/errors}. */
    public static RealtimeEvent error(UUID actorId, String code, String message) {
        return create("ERROR", null, null, actorId, 1,
                Map.of("code", code, "message", message));
    }
}
