package com.syncspace.auth;

import com.syncspace.common.InvalidCredentialsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Registration, login, refresh-token rotation, and logout.
 *
 * <p>Refresh tokens are random 256-bit values. Only their SHA-256 hash is
 * stored. Refresh rotates: the presented token is revoked and a new pair is
 * issued, so a stolen token is usable at most once before rotation is
 * detected (reuse of a revoked token fails outright).</p>
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final long refreshTtlDays;

    public AuthService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            @Value("${syncspace.jwt.refresh-ttl-days:7}") long refreshTtlDays) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTtlDays = refreshTtlDays;
    }

    @Transactional
    public AuthResponse register(String email, String displayName, String password) {
        String normalizedEmail = email.trim().toLowerCase();
        if (users.existsByEmail(normalizedEmail)) {
            throw new IllegalStateException("An account with this email already exists");
        }
        User user = new User(normalizedEmail, displayName.trim(), passwordEncoder.encode(password));
        users.save(user);
        return issueTokenPair(user);
    }

    @Transactional
    public AuthResponse login(String email, String password) {
        User user = users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }
        return issueTokenPair(user);
    }

    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokens.findByTokenHash(sha256Hex(rawRefreshToken))
                .orElseThrow(() -> new InvalidCredentialsException("Invalid refresh token"));
        if (!stored.isActive()) {
            throw new InvalidCredentialsException("Refresh token is expired or revoked");
        }
        RefreshToken replacement = createRefreshToken(stored.getUser());
        stored.revoke();
        stored.setReplacedBy(replacement);
        refreshTokens.save(stored);
        return new AuthResponse(
                toDto(stored.getUser()),
                jwtService.generateAccessToken(stored.getUser()),
                toRawToken(replacement),
                jwtService.getAccessTtlSeconds());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(sha256Hex(rawRefreshToken)).ifPresent(token -> {
            token.revoke();
            refreshTokens.save(token);
        });
    }

    public UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getEmail(), user.getDisplayName(), user.getAvatarUrl());
    }

    private AuthResponse issueTokenPair(User user) {
        RefreshToken refreshToken = createRefreshToken(user);
        return new AuthResponse(
                toDto(user),
                jwtService.generateAccessToken(user),
                toRawToken(refreshToken),
                jwtService.getAccessTtlSeconds());
    }

    private RefreshToken createRefreshToken(User user) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        RefreshToken token = new RefreshToken(
                user, sha256Hex(raw), Instant.now().plusSeconds(refreshTtlDays * 24 * 3600));
        // The raw value is carried back through the token itself only for the
        // response; it is never persisted. See toRawToken.
        token.setRawValueForResponse(raw);
        return refreshTokens.save(token);
    }

    /**
     * The raw refresh token is only available at creation time. It is kept on
     * the entity transiently so the service can return it once; it is never
     * written to the database.
     */
    private String toRawToken(RefreshToken token) {
        String raw = token.getRawValueForResponse();
        if (raw == null) {
            throw new IllegalStateException("Refresh token raw value is only available at creation");
        }
        return raw;
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record UserDto(UUID id, String email, String displayName, String avatarUrl) {
    }

    public record AuthResponse(UserDto user, String accessToken, String refreshToken, long expiresInSeconds) {
    }
}
