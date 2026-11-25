package com.syncspace.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STOMP over a real server port against the real database: JWT CONNECT
 * auth, subscription authorization, and local broadcast of a published
 * event. Needs local PostgreSQL and (for the Redis fan-out wiring) Redis;
 * the broadcast itself works even if Redis is down.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RealtimeEventPublisher publisher;

    private String register(String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> register = rest.exchange("/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(
                        """
                        {"email":"%s","displayName":"Test","password":"password123"}""".formatted(email),
                        headers),
                Map.class);
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) register.getBody().get("accessToken");
    }

    private String createWorkspace(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> created = rest.exchange("/api/v1/workspaces", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"Realtime WS\",\"description\":\"stomp test\"}", headers),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        // Receipt tracking (used to wait for subscriptions) needs a scheduler.
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("stomp-test-");
        scheduler.initialize();
        client.setTaskScheduler(scheduler);
        return client;
    }

    // NB: the 3-arg connectAsync(url, handler, headers) overload does NOT
    // exist — that call resolves to the (url, handler, uriVariables...)
    // varargs overload and silently drops the STOMP headers. Always use
    // the (url, handshakeHeaders, connectHeaders, handler) overload.
    private StompHeaders connectHeaders(String token) {
        StompHeaders headers = new StompHeaders();
        if (token != null) {
            headers.add("Authorization", "Bearer " + token);
        }
        return headers;
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    /**
     * Subscribes and waits briefly for the server to register it before the
     * test publishes anything. The SUBSCRIBE frame is processed
     * asynchronously on the server's inbound channel (observed ~120 ms
     * behind the publish in a debug run), and the simple broker never
     * replays — a publish that overtakes the SUBSCRIBE is lost. Spring's
     * simple broker sends no RECEIPT for SUBSCRIBE, so a receipt wait is
     * not possible; the 500 ms pause covers the observed lag with margin.
     */
    private void waitForSubscription(StompSession session, String destination,
                                     StompFrameHandler handler) throws Exception {
        session.subscribe(destination, handler);
        Thread.sleep(500);
    }

    /** Captures ERROR frames and transport failures; completes {@code connected} accordingly. */
    private static class CaptureHandler extends StompSessionHandlerAdapter {
        final CompletableFuture<Boolean> connected = new CompletableFuture<>();
        final LinkedBlockingQueue<Map<String, Object>> errors = new LinkedBlockingQueue<>();

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            connected.complete(true);
        }

        @Override
        public void handleException(StompSession session, StompCommand command,
                                    StompHeaders headers, byte[] payload, Throwable exception) {
            if (command == StompCommand.ERROR) {
                errors.add(Map.of("error", new String(payload)));
            }
            connected.complete(false);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            connected.complete(false);
        }
    }

    private static class MapFrameHandler implements StompFrameHandler {
        final LinkedBlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return Map.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders headers, Object payload) {
            frames.add((Map<String, Object>) payload);
        }
    }

    @Test
    void memberConnectsSubscribesAndReceivesPublishedEvent() throws Exception {
        String token = register("rt-owner-" + UUID.randomUUID() + "@example.com");
        String workspaceId = createWorkspace(token);

        CaptureHandler handler = new CaptureHandler();
        WebSocketStompClient client = stompClient();
        StompSession session = client.connectAsync(wsUrl(), new WebSocketHttpHeaders(), connectHeaders(token), handler).get(5, TimeUnit.SECONDS);
        assertThat(handler.connected.get(5, TimeUnit.SECONDS)).isTrue();

        MapFrameHandler messages = new MapFrameHandler();
        waitForSubscription(session,
                "/topic/workspaces/" + workspaceId + "/messages", messages);

        RealtimeEvent event = RealtimeEvent.create("MESSAGE_CREATED", UUID.fromString(workspaceId),
                UUID.randomUUID(), UUID.randomUUID(), 1, Map.of("channelId", "c1"));
        publisher.publishToWorkspaceTopic(event, "messages");

        Map<String, Object> received = messages.frames.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.get("eventId")).isEqualTo(event.eventId());
        assertThat(received.get("eventType")).isEqualTo("MESSAGE_CREATED");
        assertThat(received.get("workspaceId")).isEqualTo(workspaceId);
        session.disconnect();
    }

    @Test
    void connectWithInvalidTokenIsRejected() throws Exception {
        CaptureHandler handler = new CaptureHandler();
        WebSocketStompClient client = stompClient();
        try {
            client.connectAsync(wsUrl(), new WebSocketHttpHeaders(), connectHeaders("not-a-real-token"), handler).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A failed connect future is itself a rejection.
        }
        // Either the connect future failed or an ERROR frame arrived.
        assertThat(handler.connected.get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void connectWithoutTokenIsRejected() throws Exception {
        CaptureHandler handler = new CaptureHandler();
        WebSocketStompClient client = stompClient();
        try {
            client.connectAsync(wsUrl(), new WebSocketHttpHeaders(), connectHeaders(null), handler).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A failed connect future is itself a rejection.
        }
        assertThat(handler.connected.get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void nonMemberSubscriptionIsRefusedWithErrorOnUserQueue() throws Exception {
        String ownerToken = register("rt-owner2-" + UUID.randomUUID() + "@example.com");
        String workspaceId = createWorkspace(ownerToken);
        String strangerToken = register("rt-stranger-" + UUID.randomUUID() + "@example.com");

        CaptureHandler handler = new CaptureHandler();
        WebSocketStompClient client = stompClient();
        StompSession session = client.connectAsync(wsUrl(), new WebSocketHttpHeaders(), connectHeaders(strangerToken), handler)
                .get(5, TimeUnit.SECONDS);
        assertThat(handler.connected.get(5, TimeUnit.SECONDS)).isTrue();

        MapFrameHandler errors = new MapFrameHandler();
        waitForSubscription(session, "/user/queue/errors", errors);
        session.subscribe("/topic/workspaces/" + workspaceId + "/messages", new MapFrameHandler());

        Map<String, Object> error = errors.frames.poll(5, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.get("eventType")).isEqualTo("ERROR");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) error.get("payload");
        assertThat(payload.get("code")).isEqualTo("FORBIDDEN");
        session.disconnect();
    }
}
