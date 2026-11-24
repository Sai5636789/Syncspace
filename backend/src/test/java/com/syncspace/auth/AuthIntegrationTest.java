package com.syncspace.auth;

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
 * Full auth flow against the real database (Flyway V1): register, login,
 * authenticated /me, refresh rotation, logout, and reuse of a revoked
 * refresh token. Each test uses a fresh unique email because the server
 * commits on its own threads — the test's transaction cannot roll that back.
 * (The users created here are test data in the local dev database.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<Map> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), Map.class);
    }

    private String uniqueEmail() {
        return "test-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) {
        ResponseEntity<Map> register = post("/api/v1/auth/register",
                """
                {"email":"%s","displayName":"Sai","password":"password123"}""".formatted(email));
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) register.getBody().get("refreshToken");
    }

    @Test
    void fullAuthFlow() {
        String email = uniqueEmail();
        String refreshToken = register(email);

        // Login with the same credentials returns a fresh pair.
        ResponseEntity<Map> login = post("/api/v1/auth/login",
                """
                {"email":"%s","password":"password123"}""".formatted(email));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = (String) login.getBody().get("accessToken");
        assertThat(accessToken).isNotBlank();

        // /me works with the access token and fails without one.
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(accessToken);
        ResponseEntity<Map> me = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("email")).isEqualTo(email);

        ResponseEntity<Map> meAnonymous = rest.getForEntity("/api/v1/auth/me", Map.class);
        assertThat(meAnonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Refresh rotates: a new refresh token comes back, the old one dies.
        ResponseEntity<Map> refreshed = post("/api/v1/auth/refresh",
                """
                {"refreshToken":"%s"}""".formatted(refreshToken));
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newRefreshToken = (String) refreshed.getBody().get("refreshToken");
        assertThat(newRefreshToken).isNotBlank().isNotEqualTo(refreshToken);

        ResponseEntity<Map> reuseOld = post("/api/v1/auth/refresh",
                """
                {"refreshToken":"%s"}""".formatted(refreshToken));
        assertThat(reuseOld.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Logout revokes the current refresh token.
        ResponseEntity<Map> logout = post("/api/v1/auth/logout",
                """
                {"refreshToken":"%s"}""".formatted(newRefreshToken));
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> afterLogout = post("/api/v1/auth/refresh",
                """
                {"refreshToken":"%s"}""".formatted(newRefreshToken));
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void duplicateRegistrationIsConflict() {
        String email = uniqueEmail();
        register(email);

        ResponseEntity<Map> second = post("/api/v1/auth/register",
                """
                {"email":"%s","displayName":"Sai","password":"password123"}""".formatted(email));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void wrongPasswordIsUnauthorized() {
        String email = uniqueEmail();
        register(email);

        ResponseEntity<Map> login = post("/api/v1/auth/login",
                """
                {"email":"%s","password":"not-the-password"}""".formatted(email));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
