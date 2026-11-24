package com.syncspace.auth;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-chars-long!";

    private User user() {
        User user = new User("sai@example.com", "Sai", "hashed");
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void generateAndParseRoundTrip() {
        JwtService jwtService = new JwtService(SECRET, 15);
        User user = user();

        String token = jwtService.generateAccessToken(user);

        assertThat(jwtService.parseUserId(token)).contains(user.getId());
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService jwtService = new JwtService(SECRET, 15);
        String token = jwtService.generateAccessToken(user());

        String tampered = token.substring(0, token.length() - 2) + "aa";

        assertThat(jwtService.parseUserId(tampered)).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService jwtService = new JwtService(SECRET, -1);
        String token = jwtService.generateAccessToken(user());

        assertThat(jwtService.parseUserId(token)).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtService issuer = new JwtService(SECRET, 15);
        JwtService other = new JwtService("a-different-secret-that-is-also-32-chars!", 15);

        String token = issuer.generateAccessToken(user());

        assertThat(other.parseUserId(token)).isEmpty();
    }
}
