package com.syncspace.realtime;

import com.syncspace.auth.JwtService;
import com.syncspace.workspace.WorkspaceMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates and authorizes STOMP frames on the inbound channel.
 *
 * <ul>
 *   <li>CONNECT: the {@code Authorization: Bearer <access-token>} header is
 *       validated with the same {@link JwtService} as the REST filter.
 *       Missing, invalid, or expired tokens reject the connection with an
 *       ERROR frame. No token is ever accepted from a query parameter.</li>
 *   <li>SUBSCRIBE: subscriptions to {@code /topic/workspaces/{id}/...}
 *       require membership of that workspace. Non-members get an ERROR
 *       envelope on {@code /user/queue/errors} and the subscription is
 *       refused — the events are never sent. {@code /user/queue/*}
 *       destinations only need authentication.</li>
 *   <li>MESSAGE (client → {@code /app/...}): per-connection rate limiting,
 *       60 application messages per minute. Over the limit the frame is
 *       dropped and a RATE_LIMITED error goes to {@code /user/queue/errors}.</li>
 * </ul>
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    /** Application messages allowed per connection per minute (docs/websocket-events.md). */
    public static final int MAX_MESSAGES_PER_MINUTE = 60;

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    private static final long WINDOW_MILLIS = 60_000L;

    private final JwtService jwtService;
    private final WorkspaceMemberRepository members;
    private final SimpMessagingTemplate messagingTemplate;
    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    public StompAuthChannelInterceptor(
            JwtService jwtService,
            WorkspaceMemberRepository members,
            // @Lazy: the broker messaging template is created by the same
            // WebSocket configuration that depends on this interceptor;
            // a lazy proxy breaks that construction cycle.
            @Lazy SimpMessagingTemplate messagingTemplate) {
        this.jwtService = jwtService;
        this.members = members;
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = accessorFor(message);
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message; // heartbeat frame
        }
        return switch (command) {
            case CONNECT -> authenticateConnect(accessor, message);
            case SUBSCRIBE -> authorizeSubscribe(accessor, message);
            case MESSAGE -> rateLimit(accessor, message);
            case DISCONNECT -> {
                rateWindows.remove(accessor.getSessionId());
                yield message;
            }
            default -> message;
        };
    }

    /**
     * The accessor attached to the message — the same instance
     * {@code StompSubProtocolHandler} holds. Setting the user on it fires
     * the handler's user-change callback, which is what binds the user to
     * the session for later SUBSCRIBE/MESSAGE frames. A fresh
     * {@code StompHeaderAccessor.wrap(message)} creates an unrelated copy:
     * setting the user on that copy never reaches the session, so every
     * later frame would look unauthenticated. Falls back to wrap() for
     * messages built without an attached accessor (unit tests).
     */
    private static StompHeaderAccessor accessorFor(Message<?> message) {
        StompHeaderAccessor attached =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        return attached != null ? attached : StompHeaderAccessor.wrap(message);
    }

    private Message<?> authenticateConnect(StompHeaderAccessor accessor, Message<?> message) {
        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new MessagingException("Missing Authorization header on CONNECT");
        }
        UUID userId = jwtService.parseUserId(authorization.substring("Bearer ".length()))
                .orElseThrow(() -> new MessagingException("Invalid or expired token"));
        accessor.setUser(new StompPrincipal(userId));
        if (MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class) == null) {
            // No accessor attached to this message (e.g. a message built by
            // hand in a unit test): the user lives only in the accessor, so
            // rebuild the message from its headers to carry it.
            return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
        }
        return message;
    }

    private Message<?> authorizeSubscribe(StompHeaderAccessor accessor, Message<?> message) {
        Principal user = accessor.getUser();
        if (user == null) {
            return null;
        }
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith("/topic/workspaces/")) {
            return message; // /user/queue/* and other non-workspace topics: auth only
        }
        UUID workspaceId = parseWorkspaceId(destination);
        UUID userId = UUID.fromString(user.getName());
        if (workspaceId == null) {
            sendError(user, "FORBIDDEN", "Invalid workspace subscription");
            return null;
        }
        if (!members.existsByWorkspaceIdAndUserId(workspaceId, userId)) {
            log.info("Refused subscription to workspace {} for non-member {}", workspaceId, userId);
            sendError(user, "FORBIDDEN", "You are not a member of this workspace");
            return null;
        }
        return message;
    }

    private Message<?> rateLimit(StompHeaderAccessor accessor, Message<?> message) {
        Principal user = accessor.getUser();
        if (user == null) {
            return null;
        }
        String sessionId = accessor.getSessionId();
        RateWindow window = rateWindows.computeIfAbsent(sessionId, id -> new RateWindow());
        synchronized (window) {
            long now = System.currentTimeMillis();
            if (now - window.windowStartMillis >= WINDOW_MILLIS) {
                window.windowStartMillis = now;
                window.count = 0;
            }
            if (++window.count > MAX_MESSAGES_PER_MINUTE) {
                sendError(user, "RATE_LIMITED",
                        "Rate limit exceeded: max " + MAX_MESSAGES_PER_MINUTE + " messages per minute");
                return null;
            }
        }
        return message;
    }

    private void sendError(Principal user, String code, String message) {
        messagingTemplate.convertAndSendToUser(
                user.getName(), "/queue/errors",
                RealtimeEvent.error(UUID.fromString(user.getName()), code, message));
    }

    /** Parses the workspace id from /topic/workspaces/{id}/...; null when malformed. */
    static UUID parseWorkspaceId(String destination) {
        String[] parts = destination.split("/");
        // ["", "topic", "workspaces", "{id}", "{topic}"]
        if (parts.length < 4) {
            return null;
        }
        try {
            return UUID.fromString(parts[3]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Mutable per-connection rate-limit window; guarded by synchronizing on the instance. */
    private static final class RateWindow {
        long windowStartMillis = System.currentTimeMillis();
        int count = 0;
    }
}
