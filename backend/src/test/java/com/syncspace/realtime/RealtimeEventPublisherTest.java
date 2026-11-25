package com.syncspace.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RealtimeEventPublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private StringRedisTemplate redisTemplate;

    private ObjectMapper objectMapper;
    private RealtimeEventPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        publisher = new RealtimeEventPublisher(messagingTemplate, redisTemplate, objectMapper);
    }

    private RealtimeEvent event() {
        return RealtimeEvent.create("MESSAGE_CREATED", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, Map.of("channelId", "c1"));
    }

    @Test
    void publishBroadcastsLocallyAndFansOutToRedis() {
        RealtimeEvent event = event();
        publisher.publish(event, "/topic/workspaces/" + event.workspaceId() + "/messages");

        verify(messagingTemplate).convertAndSend(
                "/topic/workspaces/" + event.workspaceId() + "/messages", event);
        verify(redisTemplate).convertAndSend(
                eq(RealtimeEventPublisher.REDIS_CHANNEL), anyString());
    }

    @Test
    void publishStillBroadcastsLocallyWhenRedisIsDown() {
        doThrow(new RuntimeException("Redis unavailable"))
                .when(redisTemplate).convertAndSend(anyString(), anyString());

        RealtimeEvent event = event();
        assertThatCode(() -> publisher.publish(event, "/topic/x"))
                .doesNotThrowAnyException();
        verify(messagingTemplate).convertAndSend("/topic/x", event);
    }

    @Test
    void redisMessageFromAnotherInstanceIsBroadcastLocally() throws Exception {
        RealtimeEvent event = event();
        String json = objectMapper.writeValueAsString(
                new RealtimeEventPublisher.RedisEnvelope(
                        "some-other-instance",
                        "/topic/workspaces/" + event.workspaceId() + "/messages", event));

        publisher.handleRedisMessage(json);

        verify(messagingTemplate).convertAndSend(
                "/topic/workspaces/" + event.workspaceId() + "/messages", event);
    }

    @Test
    void redisMessageFromThisInstanceIsIgnored() throws Exception {
        // The publisher never publishes its own origin id, so simulate it by
        // round-tripping a publish: capture the Redis payload and feed it back.
        RealtimeEvent event = event();
        org.mockito.ArgumentCaptor<String> payload =
                org.mockito.ArgumentCaptor.forClass(String.class);
        publisher.publish(event, "/topic/x");
        verify(redisTemplate).convertAndSend(eq(RealtimeEventPublisher.REDIS_CHANNEL), payload.capture());

        // The local broadcast from publish() already happened; clear it so
        // only a second broadcast from the Redis round-trip would fail this.
        org.mockito.Mockito.clearInvocations(messagingTemplate);
        publisher.handleRedisMessage(payload.getValue());

        verify(messagingTemplate, never()).convertAndSend(eq("/topic/x"), (Object) any());
    }

    @Test
    void unparseableRedisMessageIsIgnored() {
        assertThatCode(() -> publisher.handleRedisMessage("{not json"))
                .doesNotThrowAnyException();
        verify(messagingTemplate, never()).convertAndSend(anyString(), (Object) any());
    }
}
