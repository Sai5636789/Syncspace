package com.syncspace.workspace;

import com.syncspace.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * An invitation to join a workspace. Only the SHA-256 hash of the token is
 * stored; the raw token is returned once at creation so the inviter can
 * share the link. Invitations expire (72h by default) and are single-use.
 */
@Entity
@Table(name = "workspace_invitations")
public class WorkspaceInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by_id")
    private User invitedBy;

    @Column(nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkspaceRole role;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @jakarta.persistence.Transient
    private String rawTokenForResponse;

    protected WorkspaceInvitation() {
    }

    public WorkspaceInvitation(Workspace workspace, User invitedBy, String email,
                               String tokenHash, WorkspaceRole role, Instant expiresAt) {
        this.workspace = workspace;
        this.invitedBy = invitedBy;
        this.email = email;
        this.tokenHash = tokenHash;
        this.role = role;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public User getInvitedBy() {
        return invitedBy;
    }

    public String getEmail() {
        return email;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void markAccepted() {
        this.acceptedAt = Instant.now();
    }

    public boolean isUsable() {
        return acceptedAt == null && expiresAt.isAfter(Instant.now());
    }

    public void setRawTokenForResponse(String rawToken) {
        this.rawTokenForResponse = rawToken;
    }

    public String getRawTokenForResponse() {
        return rawTokenForResponse;
    }
}
