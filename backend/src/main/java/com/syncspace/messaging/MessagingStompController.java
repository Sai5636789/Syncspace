package com.syncspace.messaging;

import com.syncspace.common.ForbiddenException;
import com.syncspace.common.NotFoundException;
import com.syncspace.realtime.RealtimeEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP handlers for chat. All authorization is enforced server-side in
 * {@link MessagingService} — membership, channel ownership, and (for REST
 * edits/deletes) authorship. Invalid frames fail with an ERROR envelope on
 * {@code /user/queue/errors}; they never reach the broadcast topics.
 */
@Controller
public class MessagingStompController {

    private final MessagingService messagingService;

    public MessagingStompController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    /**
     * Send a chat message. The message is persisted first; the
     * MESSAGE_CREATED broadcast goes out only after the commit. A duplicate
     * {@code clientMessageId} returns the existing message with status
     * DUPLICATE and no second broadcast. The sender gets a MESSAGE_SENT
     * envelope on their own acknowledgement queue.
     */
    @MessageMapping("/workspaces/{workspaceId}/chat")
    @SendToUser("/queue/acknowledgements")
    public RealtimeEvent sendMessage(
            @DestinationVariable UUID workspaceId,
            @Valid @Payload SendMessageRequest request,
            Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        MessagingService.SendResult result = messagingService.sendMessage(
                userId, workspaceId, request.channelId(), request.clientMessageId(), request.body());
        return RealtimeEvent.create("MESSAGE_SENT", workspaceId, result.message().id(), userId, 1,
                Map.of("status", result.duplicate() ? "DUPLICATE" : "SENT",
                        "message", result.message()));
    }

    /** Typing indicator — broadcast ephemerally, never persisted. */
    @MessageMapping("/workspaces/{workspaceId}/typing")
    public void typing(
            @DestinationVariable UUID workspaceId,
            @Valid @Payload TypingRequest request,
            Principal principal) {
        messagingService.broadcastTyping(
                UUID.fromString(principal.getName()), workspaceId,
                request.channelId(), request.typing());
    }

    /** Best-effort delivery acknowledgement; nothing is persisted. */
    @MessageMapping("/workspaces/{workspaceId}/ack")
    public void acknowledge(
            @DestinationVariable UUID workspaceId,
            @Valid @Payload AckRequest request,
            Principal principal) {
        messagingService.acknowledge(
                UUID.fromString(principal.getName()), workspaceId, request.messageId());
    }

    // -- errors: ERROR envelopes on the sender's own error queue ----------

    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    @SendToUser("/queue/errors")
    public RealtimeEvent handleValidation(MethodArgumentNotValidException ex, Principal principal) {
        return error(principal, "VALIDATION_FAILED", "Message validation failed");
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

    @MessageExceptionHandler(IllegalStateException.class)
    @SendToUser("/queue/errors")
    public RealtimeEvent handleConflict(IllegalStateException ex, Principal principal) {
        return error(principal, "CONFLICT", ex.getMessage());
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

    public record SendMessageRequest(
            @NotNull UUID channelId,
            @NotNull UUID clientMessageId,
            @NotBlank @Size(max = MessagingService.MAX_BODY_LENGTH) String body) {
    }

    public record TypingRequest(
            @NotNull UUID channelId,
            @NotNull Boolean typing) {
    }

    public record AckRequest(
            @NotNull UUID messageId) {
    }
}
