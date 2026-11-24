package com.syncspace.auth;

import com.syncspace.configuration.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auth controller slice. The real security filter chain runs; JwtService is
 * mocked so /me can be exercised with a stubbed Bearer token.
 */
@WebMvcTest(controllers = AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserRepository users;

    @MockitoBean
    private JwtService jwtService;

    private AuthService.AuthResponse sampleResponse() {
        return new AuthService.AuthResponse(
                new AuthService.UserDto(UUID.randomUUID(), "sai@example.com", "Sai", null),
                "access-token",
                "refresh-token",
                900);
    }

    @Test
    void registerReturnsCreatedWithTokens() throws Exception {
        when(authService.register(anyString(), anyString(), anyString())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"sai@example.com","displayName":"Sai","password":"password123"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.user.email").value("sai@example.com"));
    }

    @Test
    void registerRejectsShortPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"sai@example.com","displayName":"Sai","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void registerRejectsInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","displayName":"Sai","password":"password123"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginReturnsTokens() throws Exception {
        when(authService.login(anyString(), anyString())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"sai@example.com","password":"password123"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        UUID id = UUID.randomUUID();
        User user = new User("sai@example.com", "Sai", "hashed");
        when(jwtService.parseUserId("valid-token")).thenReturn(java.util.Optional.of(id));
        when(users.findById(id)).thenReturn(java.util.Optional.of(user));
        when(authService.toDto(user)).thenReturn(new AuthService.UserDto(id, "sai@example.com", "Sai", null));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("sai@example.com"));
    }

    @Test
    void meWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
