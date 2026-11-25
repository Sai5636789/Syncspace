package com.syncspace.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Publishes committed domain events to connected clients.
 *
 * <p>Every event is broadcast locally through the STOMP broker <em>and</em>
 * published to a Redis Pub/Sub channel so other backend instances can
 * broadcast it to their own sessions. The Redis publish is best-effort:
 * if Redis is down the event is still broadcast locally (single-instance
 * chat keeps working) and a warning is logged — Redis is transport only,
 * never the durable store, and clients recover missed durable state from
 * PostgreSQL after reconnect. Delivery is at-least-once by design:
 * consumers deduplicate on {@code eventId}; exactly-once is never claimed.
 *
 * <p>To avoid double-broadcasting on the publishing instance, the Redis
 * payload wraps the envelope with the publishing instance's id; the
 * listener ignores messages that originated on this instance. The wrapper
 * never reaches clients — they only ever see the envelope.
 *
 * <p>"Best-effort" is enforced, not just intended: the Redis publish runs
 * on a bounded single-thread executor (see {@code RedisRealtimeConfig}),
 * never on the caller's thread. Lettuce queues commands while it
 * reconnects, so a synchronous publish during an outage would stall the
 * caller — chat acks, REST responses — for up to the 60 s command
 * timeout; here the caller only ever pays for the local broadcast, and
 * the background publish fails fast, logs a warning, and is dropped if
 * the executor's queue is full. Fan-out resumes on its own when Redis
 * comes back.
 *
 * <p>Callers must publish only after the database transaction commits
 * (see the {@code afterCommit} hook in the messaging service).
 */
@Component
public class RealtimeEventPublisher {

    /** Redis Pub/Sub channel carrying wrapped realtime events. */
    public static final String REDIS_CHANNEL = "syncspace-events";

    private static final Logger log = LoggerFactory.getLogger(RealtimeEventPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Executor redisPublishExecutor;
    private final String instanceId = UUID.randomUUID().toString();

    public RealtimeEventPublisher(
            SimpMessagingTemplate messagingTemplate,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Qualifier("realtimePublishExecutor") Executor redisPublishExecutor) {
        this.messagingTemplate = messagingTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.redisPublishExecutor = redisPublishExecutor;
    }

    /** Broadcast to an explicit STOMP destination and fan out on Redis. */
    public void publish(RealtimeEvent event, String destination) {
        messagingTemplate.convertAndSend(destination, event);
        publishToRedis(event, destination);
    }

    /** Broadcast to a workspace topic, e.g. {@code messages} or {@code typing}. */
    public void publishToWorkspaceTopic(RealtimeEvent event, String topicSuffix) {
        publish(event, "/topic/workspaces/" + event.workspaceId() + "/" + topicSuffix);
    }

    /**
     * Send an event to one user ({@code /user/queue/...} destinations).
     * Fanned out on Redis like topic events: each instance attempts the
     * user-destination send and only the instance holding the user's
     * session delivers it.
     */
    public void publishToUser(UUID userId, RealtimeEvent event, String queueSuffix) {
        String destination = "/user/" + userId + queueSuffix;
        messagingTemplate.convertAndSendToUser(userId.toString(), queueSuffix, event);
        publishToRedis(event, destination);
    }

    /**
     * Handle a message received from the Redis channel: broadcast it locally
     * unless this instance published it (already broadcast above).
     */
    public void handleRedisMessage(String json) {
        RedisEnvelope envelope;
        try {
            envelope = objectMapper.readValue(json, RedisEnvelope.class);
        } catch (JsonProcessingException ex) {
            log.warn("Ignoring unparseable realtime event from Redis: {}", ex.getMessage());
            return;
        }
        if (instanceId.equals(envelope.origin())) {
            return;
        }
        messagingTemplate.convertAndSend(envelope.destination(), envelope.event());
    }

    private void publishToRedis(RealtimeEvent event, String destination) {
        try {
            // Runs off the caller's thread: a Redis outage must never stall
            // the local broadcast or the request that triggered the publish.
            redisPublishExecutor.execute(() -> {
                try {
                    String json = objectMapper.writeValueAsString(
                            new RedisEnvelope(instanceId, destination, event));
                    redisTemplate.convertAndSend(REDIS_CHANNEL, json);
                } catch (Exception ex) {
                    // Best-effort: the local broadcast above already went out.
                    log.warn("Redis Pub/Sub publish failed (event still broadcast locally): {}",
                            ex.getMessage());
                }
            });
        } catch (Exception ex) {
            // The executor itself refused the task (queue full); same deal.
            log.warn("Redis Pub/Sub publish dropped (event still broadcast locally): {}",
                    ex.getMessage());
        }
    }

    /** Redis payload: the envelope plus routing info that stays server-side. */
    record RedisEnvelope(String origin, String destination, RealtimeEvent event) {
    }
}
