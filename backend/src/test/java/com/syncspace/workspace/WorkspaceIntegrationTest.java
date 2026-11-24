package com.syncspace.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workspace flow against the real database: create, invite, accept, role
 * enforcement, ownership transfer, delete. Each run uses fresh unique
 * emails; the users are test data in the local dev database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

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

    @Test
    void workspaceInviteAcceptTransferDeleteFlow() {
        String ownerToken = register(uniqueEmail("ws-owner"));
        String memberEmail = uniqueEmail("ws-member");
        String memberToken = register(memberEmail);

        // Owner creates a workspace.
        ResponseEntity<Map> created = rest.exchange("/api/v1/workspaces", HttpMethod.POST,
                authEntity(ownerToken,
                        """
                        {"name":"Alpha Team","description":"Day 1 test workspace"}"""),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String workspaceId = (String) created.getBody().get("id");
        assertThat(created.getBody().get("role")).isEqualTo("OWNER");

        // Non-member cannot see it.
        ResponseEntity<Map> hidden = rest.exchange(
                "/api/v1/workspaces/" + workspaceId, HttpMethod.GET, authEntity(memberToken), Map.class);
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Owner invites the member; member accepts the token.
        ResponseEntity<Map> invitation = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/invitations", HttpMethod.POST,
                authEntity(ownerToken,
                        """
                        {"email":"%s","role":"MEMBER"}""".formatted(memberEmail)),
                Map.class);
        assertThat(invitation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = (String) invitation.getBody().get("token");
        assertThat(token).isNotBlank();

        ResponseEntity<Map> accepted = rest.exchange(
                "/api/v1/workspaces/invitations/" + token + "/accept", HttpMethod.POST,
                authEntity(memberToken, null), Map.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody().get("role")).isEqualTo("MEMBER");

        // Member can now read it, but cannot delete it.
        ResponseEntity<Map> visible = rest.exchange(
                "/api/v1/workspaces/" + workspaceId, HttpMethod.GET, authEntity(memberToken), Map.class);
        assertThat(visible.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> memberDelete = rest.exchange(
                "/api/v1/workspaces/" + workspaceId, HttpMethod.DELETE, authEntity(memberToken), Map.class);
        assertThat(memberDelete.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Owner transfers ownership; the new owner deletes the workspace.
        ResponseEntity<java.util.List<Map<String, Object>>> membersList = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/members", HttpMethod.GET,
                authEntity(ownerToken),
                new org.springframework.core.ParameterizedTypeReference<java.util.List<Map<String, Object>>>() {
                });
        assertThat(membersList.getStatusCode()).isEqualTo(HttpStatus.OK);
        String memberUserId = membersList.getBody().stream()
                .filter(entry -> memberEmail.equals(entry.get("email")))
                .map(entry -> (String) entry.get("userId"))
                .findFirst()
                .orElseThrow();
        ResponseEntity<Map> transfer = rest.exchange(
                "/api/v1/workspaces/" + workspaceId + "/transfer", HttpMethod.POST,
                authEntity(ownerToken,
                        """
                        {"newOwnerId":"%s"}""".formatted(memberUserId)),
                Map.class);
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> ownerDelete = rest.exchange(
                "/api/v1/workspaces/" + workspaceId, HttpMethod.DELETE, authEntity(memberToken), Map.class);
        assertThat(ownerDelete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> gone = rest.exchange(
                "/api/v1/workspaces/" + workspaceId, HttpMethod.GET, authEntity(ownerToken), Map.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
