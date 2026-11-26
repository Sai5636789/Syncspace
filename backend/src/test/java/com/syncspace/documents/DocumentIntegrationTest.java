package com.syncspace.documents;

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
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
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

/**
 * Documents against the real database: create/list/get/rename/delete,
 * non-member 404s, cross-workspace isolation, role enforcement, and the
 * DOCUMENT_UPDATED broadcast on rename.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentIntegrationTest {

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
                authEntity(token, "{\"name\":\"%s\"}".formatted(name)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    private void inviteAndAccept(String ownerToken, String workspaceId,
                                 String memberEmail, String memberToken, String role) {
        ResponseEntity<Map> invitation = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/invitations", HttpMethod.POST,
                authEntity(ownerToken,
                        "{\"email\":\"%s\",\"role\":\"%s\"}".formatted(memberEmail, role)),
                Map.class);
        assertThat(invitation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> accepted = rest.exchange(
                "/api/v1/workspaces/invitations/" + invitation.getBody().get("token") + "/accept",
                HttpMethod.POST, authEntity(memberToken, null), Map.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String createDocument(String token, String workspaceId, String title) {
        ResponseEntity<Map> created = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/documents", HttpMethod.POST,
                authEntity(token, "{\"title\":\"%s\"}".formatted(title)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    // -- STOMP helpers (same shape as MessagingIntegrationTest) ------------

    private static class CaptureHandler extends StompSessionHandlerAdapter {
        final CompletableFuture<Boolean> connected = new CompletableFuture<>();

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            connected.complete(true);
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

    private StompSession connect(String token) throws Exception {
        CaptureHandler handler = new CaptureHandler();
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("stomp-doc-test-");
        scheduler.initialize();
        client.setTaskScheduler(scheduler);
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync(
                        "ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers, handler)
                .get(5, TimeUnit.SECONDS);
        assertThat(handler.connected.get(5, TimeUnit.SECONDS)).isTrue();
        return session;
    }

    @Test
    void documentCrudAndRenameBroadcast() throws Exception {
        String ownerEmail = uniqueEmail("doc-owner");
        String memberEmail = uniqueEmail("doc-member");
        String ownerToken = register(ownerEmail, "Owner");
        String memberToken = register(memberEmail, "Member");
        String strangerToken = register(uniqueEmail("doc-stranger"), "Stranger");
        String workspaceId = createWorkspace(ownerToken, "Docs WS");
        inviteAndAccept(ownerToken, workspaceId, memberEmail, memberToken, "MEMBER");

        // Empty list for a member.
        ResponseEntity<Map> empty = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/documents", HttpMethod.GET,
                authEntity(ownerToken), Map.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) empty.getBody().get("documents")).isEmpty();

        // Non-member: 404 on list and create (existence not leaked).
        ResponseEntity<Map> hidden = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/documents", HttpMethod.GET,
                authEntity(strangerToken), Map.class);
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> hiddenCreate = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/documents", HttpMethod.POST,
                authEntity(strangerToken, "{\"title\":\"nope\"}"), Map.class);
        assertThat(hiddenCreate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Create + list + get.
        String documentId = createDocument(memberToken, workspaceId, "Plan");
        ResponseEntity<Map> listed = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/documents", HttpMethod.GET,
                authEntity(ownerToken), Map.class);
        assertThat(((List<?>) listed.getBody().get("documents"))).hasSize(1);
        ResponseEntity<Map> fetched = rest.exchange(
                "/api/v1/documents/" + documentId, HttpMethod.GET, authEntity(memberToken), Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("title")).isEqualTo("Plan");
        assertThat(fetched.getBody().get("workspaceId")).isEqualTo(workspaceId);
        assertThat(fetched.getBody().get("version")).isEqualTo(0);

        // Stranger cannot fetch the document by id.
        ResponseEntity<Map> hiddenGet = rest.exchange(
                "/api/v1/documents/" + documentId, HttpMethod.GET, authEntity(strangerToken), Map.class);
        assertThat(hiddenGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Blank title is a 400.
        ResponseEntity<Map> blank = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/documents", HttpMethod.POST,
                authEntity(ownerToken, "{\"title\":\"  \"}"), Map.class);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Rename broadcasts DOCUMENT_UPDATED to workspace subscribers.
        StompSession session = connect(ownerToken);
        MapFrameHandler docs = new MapFrameHandler();
        session.subscribe("/topic/workspaces/" + workspaceId + "/documents", docs);
        Thread.sleep(500);
        ResponseEntity<Map> renamed = rest.exchange(
                "/api/v1/documents/" + documentId, HttpMethod.PATCH,
                authEntity(memberToken, "{\"title\":\"Roadmap\"}"), Map.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().get("title")).isEqualTo("Roadmap");
        assertThat(renamed.getBody().get("version")).isEqualTo(1);
        Map<String, Object> event = docs.frames.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.get("eventType")).isEqualTo("DOCUMENT_UPDATED");
        assertThat(event.get("resourceId")).isEqualTo(documentId);
        assertThat(event.get("version")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(payload.get("title")).isEqualTo("Roadmap");
        assertThat(payload.get("documentId")).isEqualTo(documentId);
        session.disconnect();

        // Delete, then get/list show it is gone.
        ResponseEntity<Void> deleted = rest.exchange(
                "/api/v1/documents/" + documentId, HttpMethod.DELETE, authEntity(ownerToken), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Map> gone = rest.exchange(
                "/api/v1/documents/" + documentId, HttpMethod.GET, authEntity(ownerToken), Map.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> afterDelete = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/documents", HttpMethod.GET,
                authEntity(ownerToken), Map.class);
        assertThat((List<?>) afterDelete.getBody().get("documents")).isEmpty();
    }

    @Test
    void crossWorkspaceIsolationAndViewerRoleEnforcement() {
        String ownerEmail = uniqueEmail("doc-x-owner");
        String viewerEmail = uniqueEmail("doc-x-viewer");
        String ownerToken = register(ownerEmail, "Owner");
        String viewerToken = register(viewerEmail, "Viewer");
        String workspaceA = createWorkspace(ownerToken, "Workspace A");
        String workspaceB = createWorkspace(ownerToken, "Workspace B");
        inviteAndAccept(ownerToken, workspaceA, viewerEmail, viewerToken, "VIEWER");

        String documentId = createDocument(ownerToken, workspaceA, "Secret plan");

        // A member of workspace A cannot reach the document through B's
        // listing, and B's list does not contain it.
        ResponseEntity<Map> listB = rest.exchange(
                "/api/v1/workspaces/" + workspaceB + "/documents", HttpMethod.GET,
                authEntity(ownerToken), Map.class);
        assertThat((List<?>) listB.getBody().get("documents")).isEmpty();

        // Viewer can read and list, but cannot create, rename, or delete.
        ResponseEntity<Map> viewerList = rest.exchange(
                "/api/v1/workspaces/" + workspaceA + "/documents", HttpMethod.GET,
                authEntity(viewerToken), Map.class);
        assertThat(((List<?>) viewerList.getBody().get("documents"))).hasSize(1);
        ResponseEntity<Map> viewerGet = rest.exchange(
                "/api/v1/documents/" + documentId, HttpMethod.GET, authEntity(viewerToken), Map.class);
        assertThat(viewerGet.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> viewerCreate = rest.exchange(
                "/api/v1/workspaces/" + workspaceA + "/documents", HttpMethod.POST,
                authEntity(viewerToken, "{\"title\":\"viewer doc\"}"), Map.class);
        assertThat(viewerCreate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> viewerRename = rest.exchange(
                "/api/v1/documents/" + documentId, HttpMethod.PATCH,
                authEntity(viewerToken, "{\"title\":\"hijack\"}"), Map.class);
        assertThat(viewerRename.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Void> viewerDelete = rest.exchange(
                "/api/v1/documents/" + documentId, HttpMethod.DELETE,
                authEntity(viewerToken), Void.class);
        assertThat(viewerDelete.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The document is untouched by the viewer's attempts.
        ResponseEntity<Map> fetched = rest.exchange(
                "/api/v1/documents/" + documentId, HttpMethod.GET, authEntity(ownerToken), Map.class);
        assertThat(fetched.getBody().get("title")).isEqualTo("Secret plan");
    }

    @Test
    void unknownDocumentIsNotFound() {
        String ownerToken = register(uniqueEmail("doc-unknown"), "Owner");
        ResponseEntity<Map> fetched = rest.exchange(
                "/api/v1/documents/" + UUID.randomUUID(), HttpMethod.GET,
                authEntity(ownerToken), Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
