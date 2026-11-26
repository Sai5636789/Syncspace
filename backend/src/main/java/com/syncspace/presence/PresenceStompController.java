package com.syncspace.presence;

import com.syncspace.common.ForbiddenException;
import com.syncspace.common.NotFoundException;
import com.syncspace.realtime.RealtimeEvent;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP heartbeat endpoint: {@code /app/workspaces/{workspaceId}/presence}.
 *
 * <p>The client sends an (empty) ping while a workspace view is open —
 * every 15 s, and once immediately on connect — and the server records
 * the connection as a live presence session in Redis. Membership is
 * enforced in {@link PresenceService}; a non-member's ping is rejected
 * with an ERROR envelope on their own error queue, never broadcast.
 * The endpoint returns nothing: presence changes go out on
 * {@code /topic/workspaces/{workspaceId}/presence} as
 * {@code PRESENCE_CHANGED} envelopes.
 */
@Controller
public class PresenceStompController {

    private final PresenceService presenceService;

    public PresenceStompController(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @MessageMapping("/workspaces/{workspaceId}/presence")
    public void heartbeat(
            @DestinationVariable UUID workspaceId,
            @Payload(required = false) HeartbeatRequest request,
            StompHeaderAccessor accessor,
            Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        presenceService.heartbeat(userId, workspaceId, accessor.getSessionId());
    }

    @MessageExceptionHandler(NotFoundException.class)
    @SendToUser("/queue/errors")
    public RealtimeEvent handleNotFound(NotFoundException ex, Principal principal) {
        return error(principal, "NOT_FOUND", ex.getMessage());
    }

    @MessageExceptionHandler(ForbiddenException.class)
    @SendToUser("/queue/errors")
    public RealtimeEvent handleForbidden(ForbiddenException ex, Principal principal) {
        return error(principal, "FORBIDDEN", ex.getMessage());
    }

    @MessageExceptionHandler(IllegalArgumentException.class)
    @SendToUser("/queue/errors")
    public RealtimeEvent handleBadRequest(IllegalArgumentException ex, Principal principal) {
        return error(principal, "BAD_REQUEST", ex.getMessage());
    }

    private static RealtimeEvent error(Principal principal, String code, String message) {
        UUID actorId = principal == null ? null : UUID.fromString(principal.getName());
        return RealtimeEvent.error(actorId, code, message);
    }

    /** Heartbeat ping body — intentionally empty; the frame itself is the signal. */
    public record HeartbeatRequest() {
    }
}
