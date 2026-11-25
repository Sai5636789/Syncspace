package com.syncspace.messaging;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Messaging against the real database and a real STOMP server: workspace
 * seeding, channel CRUD, STOMP chat with idempotency, typing, acks,
 * edit/delete authorization and broadcasts, unread tracking, pagination.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessagingIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email, String displayName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> register = rest.exchange("/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(
                        """
                        {"email":"%s","displayName":"%s","password":"password123"}"""
                                .formatted(email, displayName),
                        headers),
                Map.class);
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) register.getBody().get("accessToken");
    }

    private HttpEntity<String> authEntity(String token, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    private HttpEntity<Void> authEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private String createWorkspace(String token, String name) {
        ResponseEntity<Map> created = rest.exchange("/api/v1/workspaces", HttpMethod.POST,
                authEntity(token, "{\"name\":\"%s\",\"description\":\"msg test\"}".formatted(name)),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    private void inviteAndAccept(String ownerToken, String workspaceId, String memberEmail, String memberToken) {
        ResponseEntity<Map> invitation = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/invitations", HttpMethod.POST,
                authEntity(ownerToken,
                        "{\"email\":\"%s\",\"role\":\"MEMBER\"}".formatted(memberEmail)),
                Map.class);
        assertThat(invitation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> accepted = rest.exchange(
                "/api/v1/workspaces/invitations/" + invitation.getBody().get("token") + "/accept",
                HttpMethod.POST, authEntity(memberToken, null), Map.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // -- STOMP helpers (same shape as RealtimeIntegrationTest) ------------

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("stomp-test-");
        scheduler.initialize();
        client.setTaskScheduler(scheduler);
        return client;
    }

    private StompSession connect(String token) throws Exception {
        CaptureHandler handler = new CaptureHandler();
        WebSocketStompClient client = stompClient();
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        // NB: the 3-arg connectAsync(url, handler, headers) overload does
        // not exist — it resolves to the uriVariables varargs form and drops
        // the headers. Always use the (url, handshake, connect, handler) form.
        StompSession session = client.connectAsync(
                "ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers, handler)
                .get(5, TimeUnit.SECONDS);
        assertThat(handler.connected.get(5, TimeUnit.SECONDS)).isTrue();
        return session;
    }

    private void waitForSubscription(StompSession session, String destination,
                                     StompFrameHandler handler) throws Exception {
        session.subscribe(destination, handler);
        // SUBSCRIBE is processed asynchronously server-side; the simple
        // broker never replays, so wait for registration before publishing.
        Thread.sleep(500);
    }

    private static class CaptureHandler extends StompSessionHandlerAdapter {
        final CompletableFuture<Boolean> connected = new CompletableFuture<>();

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            connected.complete(true);
        }

        @Override
        public void handleException(StompSession session, StompCommand command,
                                    StompHeaders headers, byte[] payload, Throwable exception) {
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> messageOf(Map<String, Object> envelope) {
        return (Map<String, Object>) payloadOf(envelope).get("message");
    }

    private void sendChat(StompSession session, String workspaceId,
                          String channelId, String clientMessageId, String body) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/workspaces/" + workspaceId + "/chat");
        session.send(headers, Map.of(
                "channelId", channelId,
                "clientMessageId", clientMessageId,
                "body", body));
    }

    /** Polls REST history until it reports the expected total, or times out. */
    private void waitForHistorySize(String channelId, String token, int expected) throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            ResponseEntity<Map> probe = rest.exchange(
                    "/api/v1/channels/" + channelId + "/messages?page=0&size=50",
                    HttpMethod.GET, authEntity(token), Map.class);
            if (Integer.valueOf(expected).equals(probe.getBody().get("totalElements"))) {
                return;
            }
            Thread.sleep(200);
        }
        fail("history did not reach " + expected + " messages in time");
    }

    // -- tests --------------------------------------------------------------

    @Test
    void workspaceSeedsGeneralChannelAndChannelCrud() {
        String ownerToken = register(uniqueEmail("msg-owner"), "Owner");
        String strangerToken = register(uniqueEmail("msg-stranger"), "Stranger");
        String workspaceId = createWorkspace(ownerToken, "Chat WS");

        // The default general channel is seeded at workspace creation.
        ResponseEntity<List> channels = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/channels", HttpMethod.GET,
                authEntity(ownerToken), List.class);
        assertThat(channels.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(channels.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) channels.getBody().get(0)).get("name")).isEqualTo("general");
        assertThat(((Map<?, ?>) channels.getBody().get(0)).get("unreadCount")).isEqualTo(0);

        // Non-members cannot see or create channels (existence not leaked).
        ResponseEntity<Map> hidden = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/channels", HttpMethod.GET,
                authEntity(strangerToken), Map.class);
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> forbiddenCreate = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/channels", HttpMethod.POST,
                authEntity(strangerToken, "{\"name\":\"nope\"}"), Map.class);
        assertThat(forbiddenCreate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Create, duplicate name, blank name.
        ResponseEntity<Map> created = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/channels", HttpMethod.POST,
                authEntity(ownerToken, "{\"name\":\"random\",\"description\":\"off-topic\"}"),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("name")).isEqualTo("random");

        ResponseEntity<Map> duplicate = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/channels", HttpMethod.POST,
                authEntity(ownerToken, "{\"name\":\"random\"}"), Map.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> duplicateGeneral = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/channels", HttpMethod.POST,
                authEntity(ownerToken, "{\"name\":\"general\"}"), Map.class);
        assertThat(duplicateGeneral.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> blank = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/channels", HttpMethod.POST,
                authEntity(ownerToken, "{\"name\":\"  \"}"), Map.class);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void chatFlowOverStompAndRest() throws Exception {
        String ownerEmail = uniqueEmail("chat-owner");
        String memberEmail = uniqueEmail("chat-member");
        String ownerToken = register(ownerEmail, "Owner");
        String memberToken = register(memberEmail, "Member");
        String strangerToken = register(uniqueEmail("chat-stranger"), "Stranger");
        String workspaceId = createWorkspace(ownerToken, "Chat Flow");
        inviteAndAccept(ownerToken, workspaceId, memberEmail, memberToken);

        ResponseEntity<List> channels = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/channels", HttpMethod.GET,
                authEntity(ownerToken), List.class);
        String generalId = (String) ((Map<?, ?>) channels.getBody().get(0)).get("id");

        StompSession ownerSession = connect(ownerToken);
        MapFrameHandler messages = new MapFrameHandler();
        MapFrameHandler typing = new MapFrameHandler();
        MapFrameHandler acks = new MapFrameHandler();
        MapFrameHandler errors = new MapFrameHandler();
        waitForSubscription(ownerSession, "/topic/workspaces/" + workspaceId + "/messages", messages);
        waitForSubscription(ownerSession, "/topic/workspaces/" + workspaceId + "/typing", typing);
        waitForSubscription(ownerSession, "/user/queue/acknowledgements", acks);
        waitForSubscription(ownerSession, "/user/queue/errors", errors);

        // Owner sends the first message: broadcast + SENT ack.
        String clientMessageId = UUID.randomUUID().toString();
        sendChat(ownerSession, workspaceId, generalId, clientMessageId, "Hello team");
        Map<String, Object> created = messages.frames.poll(5, TimeUnit.SECONDS);
        assertThat(created).isNotNull();
        assertThat(created.get("eventType")).isEqualTo("MESSAGE_CREATED");
        assertThat(messageOf(created).get("body")).isEqualTo("Hello team");
        assertThat(messageOf(created).get("clientMessageId")).isEqualTo(clientMessageId);
        Map<String, Object> sentAck = acks.frames.poll(5, TimeUnit.SECONDS);
        assertThat(sentAck).isNotNull();
        assertThat(sentAck.get("eventType")).isEqualTo("MESSAGE_SENT");
        assertThat(payloadOf(sentAck).get("status")).isEqualTo("SENT");
        String ownerMessageId = (String) messageOf(created).get("id");

        // Duplicate clientMessageId: DUPLICATE ack, no second broadcast.
        sendChat(ownerSession, workspaceId, generalId, clientMessageId, "Hello again");
        Map<String, Object> dupAck = acks.frames.poll(5, TimeUnit.SECONDS);
        assertThat(dupAck).isNotNull();
        assertThat(payloadOf(dupAck).get("status")).isEqualTo("DUPLICATE");
        assertThat(messages.frames.poll(1, TimeUnit.SECONDS)).isNull();

        // Typing indicator is broadcast ephemerally.
        StompHeaders typingHeaders = new StompHeaders();
        typingHeaders.setDestination("/app/workspaces/" + workspaceId + "/typing");
        ownerSession.send(typingHeaders, Map.of("channelId", generalId, "typing", true));
        Map<String, Object> typingStarted = typing.frames.poll(5, TimeUnit.SECONDS);
        assertThat(typingStarted).isNotNull();
        assertThat(typingStarted.get("eventType")).isEqualTo("TYPING_STARTED");
        assertThat(payloadOf(typingStarted).get("displayName")).isEqualTo("Owner");

        // REST history shows exactly the one message.
        ResponseEntity<Map> history = rest.exchange(
                "/api/v1/channels/" + generalId + "/messages?page=0&size=50",
                HttpMethod.GET, authEntity(ownerToken), Map.class);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody().get("totalElements")).isEqualTo(1);
        assertThat(history.getBody().get("totalPages")).isEqualTo(1);
        assertThat(((List<?>) history.getBody().get("content"))).hasSize(1);

        // Member sends a message; owner sees it and gains one unread.
        StompSession memberSession = connect(memberToken);
        sendChat(memberSession, workspaceId, generalId, UUID.randomUUID().toString(), "Hi Owner");
        Map<String, Object> memberCreated = messages.frames.poll(5, TimeUnit.SECONDS);
        assertThat(memberCreated).isNotNull();
        assertThat(messageOf(memberCreated).get("body")).isEqualTo("Hi Owner");
        String memberMessageId = (String) messageOf(memberCreated).get("id");

        ResponseEntity<List> ownerChannels = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/channels", HttpMethod.GET,
                authEntity(ownerToken), List.class);
        assertThat(((Map<?, ?>) ownerChannels.getBody().get(0)).get("unreadCount")).isEqualTo(1);

        // Mark read clears the unread count.
        ResponseEntity<Map> read = rest.exchange(
                "/api/v1/channels/" + generalId + "/read", HttpMethod.POST,
                authEntity(ownerToken, null), Map.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<List> afterRead = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/channels", HttpMethod.GET,
                authEntity(ownerToken), List.class);
        assertThat(((Map<?, ?>) afterRead.getBody().get(0)).get("unreadCount")).isEqualTo(0);

        // Edit own message: 200 + MESSAGE_UPDATED broadcast. Others: 403.
        ResponseEntity<Map> edited = rest.exchange(
                "/api/v1/messages/" + ownerMessageId, HttpMethod.PATCH,
                authEntity(ownerToken, "{\"body\":\"Hello team (edited)\"}"), Map.class);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().get("body")).isEqualTo("Hello team (edited)");
        assertThat(edited.getBody().get("editedAt")).isNotNull();
        Map<String, Object> updated = messages.frames.poll(5, TimeUnit.SECONDS);
        assertThat(updated).isNotNull();
        assertThat(updated.get("eventType")).isEqualTo("MESSAGE_UPDATED");

        ResponseEntity<Map> editForbidden = rest.exchange(
                "/api/v1/messages/" + ownerMessageId, HttpMethod.PATCH,
                authEntity(memberToken, "{\"body\":\"hijack\"}"), Map.class);
        assertThat(editForbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> deleteForbidden = rest.exchange(
                "/api/v1/messages/" + ownerMessageId, HttpMethod.DELETE,
                authEntity(memberToken), Map.class);
        assertThat(deleteForbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Delivery ack: best-effort confirmation on the ack queue.
        StompHeaders ackHeaders = new StompHeaders();
        ackHeaders.setDestination("/app/workspaces/" + workspaceId + "/ack");
        ownerSession.send(ackHeaders, Map.of("messageId", memberMessageId));
        Map<String, Object> acked = acks.frames.poll(5, TimeUnit.SECONDS);
        assertThat(acked).isNotNull();
        assertThat(acked.get("eventType")).isEqualTo("MESSAGE_ACKNOWLEDGED");
        assertThat(payloadOf(acked).get("status")).isEqualTo("ACKED");

        // Blank body over STOMP fails validation with an ERROR envelope.
        sendChat(ownerSession, workspaceId, generalId, UUID.randomUUID().toString(), "   ");
        Map<String, Object> validationError = errors.frames.poll(5, TimeUnit.SECONDS);
        assertThat(validationError).isNotNull();
        assertThat(validationError.get("eventType")).isEqualTo("ERROR");
        assertThat(payloadOf(validationError).get("code")).isEqualTo("VALIDATION_FAILED");

        // Delete own message: 204 + MESSAGE_DELETED broadcast; history drops it.
        ResponseEntity<Void> deleted = rest.exchange(
                "/api/v1/messages/" + ownerMessageId, HttpMethod.DELETE,
                authEntity(ownerToken), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> deletedEvent = messages.frames.poll(5, TimeUnit.SECONDS);
        assertThat(deletedEvent).isNotNull();
        assertThat(deletedEvent.get("eventType")).isEqualTo("MESSAGE_DELETED");
        ResponseEntity<Map> afterDelete = rest.exchange(
                "/api/v1/channels/" + generalId + "/messages?page=0&size=50",
                HttpMethod.GET, authEntity(ownerToken), Map.class);
        assertThat(afterDelete.getBody().get("totalElements")).isEqualTo(1);

        // A stranger's chat send is rejected with an ERROR envelope.
        StompSession strangerSession = connect(strangerToken);
        MapFrameHandler strangerErrors = new MapFrameHandler();
        waitForSubscription(strangerSession, "/user/queue/errors", strangerErrors);
        sendChat(strangerSession, workspaceId, generalId, UUID.randomUUID().toString(), "intruder");
        Map<String, Object> strangerError = strangerErrors.frames.poll(5, TimeUnit.SECONDS);
        assertThat(strangerError).isNotNull();
        assertThat(strangerError.get("eventType")).isEqualTo("ERROR");

        ownerSession.disconnect();
        memberSession.disconnect();
        strangerSession.disconnect();
    }

    @Test
    void messageHistoryPagination() throws Exception {
        String ownerToken = register(uniqueEmail("page-owner"), "Owner");
        String workspaceId = createWorkspace(ownerToken, "Paging WS");
        ResponseEntity<List> channels = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/channels", HttpMethod.GET,
                authEntity(ownerToken), List.class);
        String generalId = (String) ((Map<?, ?>) channels.getBody().get(0)).get("id");

        StompSession session = connect(ownerToken);
        // Send one at a time and wait for each to be persisted before the next:
        // concurrent STOMP sends commit in arbitrary order, and history order
        // is by createdAt, so only sequential sends give a deterministic order.
        for (int i = 1; i <= 3; i++) {
            sendChat(session, workspaceId, generalId, UUID.randomUUID().toString(), "msg-" + i);
            waitForHistorySize(generalId, ownerToken, i);
        }

        ResponseEntity<Map> page0 = rest.exchange(
                "/api/v1/channels/" + generalId + "/messages?page=0&size=2",
                HttpMethod.GET, authEntity(ownerToken), Map.class);
        assertThat(page0.getBody().get("totalElements")).isEqualTo(3);
        assertThat(page0.getBody().get("totalPages")).isEqualTo(2);
        assertThat(((List<?>) page0.getBody().get("content"))).hasSize(2);
        // Chronological: page 0 holds the oldest messages.
        assertThat(((Map<?, ?>) ((List<?>) page0.getBody().get("content")).get(0)).get("body"))
                .isEqualTo("msg-1");

        ResponseEntity<Map> page1 = rest.exchange(
                "/api/v1/channels/" + generalId + "/messages?page=1&size=2",
                HttpMethod.GET, authEntity(ownerToken), Map.class);
        assertThat(((List<?>) page1.getBody().get("content"))).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) page1.getBody().get("content")).get(0)).get("body"))
                .isEqualTo("msg-3");

        // Oversized page requests are clamped to 100.
        ResponseEntity<Map> clamped = rest.exchange(
                "/api/v1/channels/" + generalId + "/messages?page=0&size=500",
                HttpMethod.GET, authEntity(ownerToken), Map.class);
        assertThat(clamped.getBody().get("size")).isEqualTo(100);

        // Negative page is a 400.
        ResponseEntity<Map> badPage = rest.exchange(
                "/api/v1/channels/" + generalId + "/messages?page=-1&size=50",
                HttpMethod.GET, authEntity(ownerToken), Map.class);
        assertThat(badPage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        session.disconnect();
    }
}
