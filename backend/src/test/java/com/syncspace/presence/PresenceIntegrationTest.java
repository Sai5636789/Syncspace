package com.syncspace.presence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Presence against the real database, real Redis, and a real STOMP
 * server: heartbeat marks online, the REST list reflects it, disconnect
 * marks offline, and TTL expiry (simulated by deleting the session keys
 * — the test never sleeps for the 45 s production TTL) is picked up by
 * the sweep and broadcast. Non-members are rejected on both transports.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "syncspace.presence.sweep-interval-ms=1000")
class PresenceIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private StringRedisTemplate redis;

    /** PRESENCE_CHANGED frames seen while waiting for a different user; shared across waits. */
    private final List<Map<String, Object>> presenceStash = new java.util.ArrayList<>();

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
                authEntity(token, "{\"name\":\"%s\",\"description\":\"presence test\"}".formatted(name)),
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

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("stomp-presence-test-");
        scheduler.initialize();
        client.setTaskScheduler(scheduler);
        return client;
    }

    private StompSession connect(String token) throws Exception {
        CaptureHandler handler = new CaptureHandler();
        WebSocketStompClient client = stompClient();
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync(
                        "ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers, handler)
                .get(5, TimeUnit.SECONDS);
        assertThat(handler.connected.get(5, TimeUnit.SECONDS)).isTrue();
        return session;
    }

    private void waitForSubscription(StompSession session, String destination,
                                     StompFrameHandler handler) throws Exception {
        session.subscribe(destination, handler);
        Thread.sleep(500);
    }

    private void sendHeartbeat(StompSession session, String workspaceId) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/workspaces/" + workspaceId + "/presence");
        session.send(headers, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    private static String statusOf(Map<String, Object> entry) {
        return (String) entry.get("status");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getPresence(String token, String workspaceId) {
        ResponseEntity<List> response = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/presence",
                HttpMethod.GET, authEntity(token), List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) (List<?>) response.getBody();
    }

    private Map<String, Object> waitForPresenceEvent(
            MapFrameHandler presence, String userId, String status, int timeoutSeconds) throws Exception {
        // Frames for other users arrive on the same queue; a wait for one
        // user must not discard another user's event, so non-matching
        // PRESENCE_CHANGED frames are stashed for later waits.
        for (Map<String, Object> frame : presenceStash) {
            Map<String, Object> payload = payloadOf(frame);
            if (userId.equals(payload.get("userId")) && status.equals(payload.get("status"))) {
                presenceStash.remove(frame);
                return frame;
            }
        }
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> frame = presence.frames.poll(1, TimeUnit.SECONDS);
            if (frame == null) {
                continue;
            }
            if (!"PRESENCE_CHANGED".equals(frame.get("eventType"))) {
                continue;
            }
            Map<String, Object> payload = payloadOf(frame);
            if (userId.equals(payload.get("userId")) && status.equals(payload.get("status"))) {
                return frame;
            }
            presenceStash.add(frame);
        }
        fail("no PRESENCE_CHANGED " + status + " for user " + userId + " within " + timeoutSeconds + "s"
                + " (stashed: " + presenceStash.stream()
                        .map(f -> payloadOf(f).get("userId") + "=" + payloadOf(f).get("status"))
                        .toList() + ")");
        return null; // unreachable
    }

    private String userIdOf(Map<String, Object> entry) {
        return (String) entry.get("userId");
    }

    /**
     * Polls GET /presence until the named member reads with the expected
     * status (heartbeats are processed asynchronously server-side, so a
     * single read right after a send can race the handler). Returns the
     * member's user id.
     */
    private String waitForStatus(String token, String workspaceId, String displayName,
                                 String status, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> list = getPresence(token, workspaceId);
            for (Map<String, Object> entry : list) {
                if (displayName.equals(entry.get("displayName"))
                        && status.equals(statusOf(entry))) {
                    return userIdOf(entry);
                }
            }
            Thread.sleep(200);
        }
        fail("member " + displayName + " did not read " + status + " within " + timeoutSeconds + "s");
        return null; // unreachable
    }

    // -- tests --------------------------------------------------------------

    @Test
    void heartbeatMarksOnlineAndPresenceListReflectsIt() throws Exception {
        String ownerEmail = uniqueEmail("presence-owner");
        String memberEmail = uniqueEmail("presence-member");
        String ownerToken = register(ownerEmail, "Owner");
        String memberToken = register(memberEmail, "Member");
        String strangerToken = register(uniqueEmail("presence-stranger"), "Stranger");
        String workspaceId = createWorkspace(ownerToken, "Presence WS");
        inviteAndAccept(ownerToken, workspaceId, memberEmail, memberToken);

        // Before any heartbeat, both members read offline with no last-seen.
        List<Map<String, Object>> before = getPresence(ownerToken, workspaceId);
        assertThat(before).hasSize(2);
        assertThat(before).allMatch(entry -> "offline".equals(statusOf(entry)));
        assertThat(before).allMatch(entry -> entry.get("lastSeenAt") == null);

        // A stranger cannot read the list at all (existence not leaked).
        ResponseEntity<Map> hidden = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/presence",
                HttpMethod.GET, authEntity(strangerToken), Map.class);
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        StompSession ownerSession = connect(ownerToken);
        StompSession memberSession = connect(memberToken);
        MapFrameHandler presence = new MapFrameHandler();
        waitForSubscription(ownerSession, "/topic/workspaces/" + workspaceId + "/presence", presence);

        sendHeartbeat(ownerSession, workspaceId);
        sendHeartbeat(memberSession, workspaceId);

        String ownerId = waitForStatus(ownerToken, workspaceId, "Owner", "online", 5);
        String memberId = waitForStatus(ownerToken, workspaceId, "Member", "online", 5);
        List<Map<String, Object>> after = getPresence(ownerToken, workspaceId);

        // Both heartbeats are broadcast on the topic...
        waitForPresenceEvent(presence, ownerId, "online", 5);
        waitForPresenceEvent(presence, memberId, "online", 5);
        // ...and the REST list shows both online with a last-seen instant.
        assertThat(after).allMatch(entry -> "online".equals(statusOf(entry)));
        assertThat(after).allMatch(entry -> entry.get("lastSeenAt") != null);

        ownerSession.disconnect();
        memberSession.disconnect();
    }

    @Test
    void nonMemberHeartbeatRejected() throws Exception {
        String ownerToken = register(uniqueEmail("presence-owner2"), "Owner");
        String strangerToken = register(uniqueEmail("presence-stranger2"), "Stranger");
        String workspaceId = createWorkspace(ownerToken, "Presence WS 2");

        StompSession strangerSession = connect(strangerToken);
        MapFrameHandler errors = new MapFrameHandler();
        waitForSubscription(strangerSession, "/user/queue/errors", errors);
        sendHeartbeat(strangerSession, workspaceId);
        Map<String, Object> error = errors.frames.poll(5, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.get("eventType")).isEqualTo("ERROR");
        assertThat(payloadOf(error).get("code")).isEqualTo("NOT_FOUND");

        // And the stranger's heartbeat left no presence behind.
        List<Map<String, Object>> list = getPresence(ownerToken, workspaceId);
        assertThat(list).hasSize(1);
        assertThat(statusOf(list.get(0))).isEqualTo("offline");

        strangerSession.disconnect();
    }

    @Test
    void disconnectMarksOfflineWhileOtherTabStaysOnline() throws Exception {
        String ownerEmail = uniqueEmail("presence-owner3");
        String memberEmail = uniqueEmail("presence-member3");
        String ownerToken = register(ownerEmail, "Owner");
        String memberToken = register(memberEmail, "Member");
        String workspaceId = createWorkspace(ownerToken, "Presence WS 3");
        inviteAndAccept(ownerToken, workspaceId, memberEmail, memberToken);

        StompSession ownerSession = connect(ownerToken);
        StompSession memberSession = connect(memberToken);
        // The member opens a second tab: two sessions for one user.
        StompSession memberSecondTab = connect(memberToken);
        MapFrameHandler presence = new MapFrameHandler();
        waitForSubscription(ownerSession, "/topic/workspaces/" + workspaceId + "/presence", presence);

        sendHeartbeat(ownerSession, workspaceId);
        sendHeartbeat(memberSession, workspaceId);
        sendHeartbeat(memberSecondTab, workspaceId);
        String memberId = waitForStatus(ownerToken, workspaceId, "Member", "online", 5);
        waitForStatus(ownerToken, workspaceId, "Owner", "online", 5);

        // Closing one tab does not take the member offline...
        memberSession.disconnect();
        Thread.sleep(1500);
        List<Map<String, Object>> stillOnline = getPresence(ownerToken, workspaceId);
        assertThat(stillOnline.stream()
                .filter(entry -> memberId.equals(userIdOf(entry)))
                .findFirst().orElseThrow().get("status")).isEqualTo("online");

        // ...closing the last one does, and the change is broadcast.
        memberSecondTab.disconnect();
        waitForPresenceEvent(presence, memberId, "offline", 5);

        // The owner heartbeats again so its own key is fresh for the read.
        sendHeartbeat(ownerSession, workspaceId);
        List<Map<String, Object>> after = getPresence(ownerToken, workspaceId);
        assertThat(after.stream()
                .filter(entry -> memberId.equals(userIdOf(entry)))
                .findFirst().orElseThrow().get("status")).isEqualTo("offline");
        assertThat(after.stream()
                .filter(entry -> memberId.equals(userIdOf(entry)))
                .findFirst().orElseThrow().get("lastSeenAt")).isNotNull();
        assertThat(after.stream()
                .filter(entry -> "Owner".equals(entry.get("displayName")))
                .findFirst().orElseThrow().get("status")).isEqualTo("online");

        ownerSession.disconnect();
    }

    @Test
    void ttlExpiryMarksOfflineViaSweep() throws Exception {
        String ownerEmail = uniqueEmail("presence-owner4");
        String memberEmail = uniqueEmail("presence-member4");
        String ownerToken = register(ownerEmail, "Owner");
        String memberToken = register(memberEmail, "Member");
        String workspaceId = createWorkspace(ownerToken, "Presence WS 4");
        inviteAndAccept(ownerToken, workspaceId, memberEmail, memberToken);

        StompSession ownerSession = connect(ownerToken);
        StompSession memberSession = connect(memberToken);
        MapFrameHandler presence = new MapFrameHandler();
        waitForSubscription(ownerSession, "/topic/workspaces/" + workspaceId + "/presence", presence);

        sendHeartbeat(memberSession, workspaceId);
        String memberId = waitForStatus(ownerToken, workspaceId, "Member", "online", 5);

        // Simulate TTL expiry without sleeping for the 45 s production TTL:
        // delete this workspace's session keys outright, without any
        // disconnect event, as a crashed client would leave behind.
        Set<String> keys = redis.keys("presence:session:" + workspaceId + ":*");
        assertThat(keys).isNotEmpty();
        redis.delete(keys);
        // The member session is left connected but silent, like a dead tab;
        // the sweep (1 s interval in this test) must notice and broadcast.
        waitForPresenceEvent(presence, memberId, "offline", 10);
        List<Map<String, Object>> after = getPresence(ownerToken, workspaceId);
        assertThat(after.stream()
                .filter(entry -> memberId.equals(userIdOf(entry)))
                .findFirst().orElseThrow().get("status")).isEqualTo("offline");

        memberSession.disconnect();
        ownerSession.disconnect();
    }

    // -- STOMP helpers ------------------------------------------------------

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
}
