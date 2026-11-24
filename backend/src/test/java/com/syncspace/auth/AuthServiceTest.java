package com.syncspace.auth;

import com.syncspace.common.InvalidCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-chars-long!";

    @Mock
    private UserRepository users;

    @Mock
    private RefreshTokenRepository refreshTokens;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(users, refreshTokens, passwordEncoder, new JwtService(SECRET, 15), 7);
    }

    private User persistedUser(String email, String rawPassword) {
        User user = new User(email, "Sai", passwordEncoder.encode(rawPassword));
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void registerHashesPasswordAndReturnsTokens() {
        when(users.existsByEmail("sai@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });
        when(refreshTokens.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthService.AuthResponse response = authService.register("Sai@Example.com", "Sai", "password123");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(passwordEncoder.matches("password123", captor.getValue().getPasswordHash())).isTrue();
        assertThat(captor.getValue().getEmail()).isEqualTo("sai@example.com");
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(users.existsByEmail("sai@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register("sai@example.com", "Sai", "password123"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        User user = persistedUser("sai@example.com", "password123");
        when(users.findByEmail("sai@example.com")).thenReturn(Optional.of(user));
        when(refreshTokens.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthService.AuthResponse response = authService.login("sai@example.com", "password123");

        assertThat(response.user().email()).isEqualTo("sai@example.com");
        assertThat(response.accessToken()).isNotBlank();
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = persistedUser("sai@example.com", "password123");
        when(users.findByEmail("sai@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login("sai@example.com", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshRotatesTokenAndRevokesOldOne() {
        User user = persistedUser("sai@example.com", "password123");
        String raw = "old-refresh-token-value";
        RefreshToken stored = new RefreshToken(user, AuthService.sha256Hex(raw), Instant.now().plusSeconds(3600));
        when(refreshTokens.findByTokenHash(AuthService.sha256Hex(raw))).thenReturn(Optional.of(stored));
        when(refreshTokens.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthService.AuthResponse response = authService.refresh(raw);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank().isNotEqualTo(raw);
        assertThat(stored.getRevokedAt()).isNotNull();
    }

    @Test
    void refreshRejectsRevokedToken() {
        User user = persistedUser("sai@example.com", "password123");
        String raw = "revoked-refresh-token";
        RefreshToken stored = new RefreshToken(user, AuthService.sha256Hex(raw), Instant.now().plusSeconds(3600));
        stored.revoke();
        when(refreshTokens.findByTokenHash(AuthService.sha256Hex(raw))).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.refresh(raw))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void logoutRevokesToken() {
        User user = persistedUser("sai@example.com", "password123");
        String raw = "logout-refresh-token";
        RefreshToken stored = new RefreshToken(user, AuthService.sha256Hex(raw), Instant.now().plusSeconds(3600));
        when(refreshTokens.findByTokenHash(AuthService.sha256Hex(raw))).thenReturn(Optional.of(stored));

        authService.logout(raw);

        assertThat(stored.getRevokedAt()).isNotNull();
        verify(refreshTokens).save(stored);
    }
}
