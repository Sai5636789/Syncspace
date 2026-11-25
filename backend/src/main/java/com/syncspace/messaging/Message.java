package com.syncspace.messaging;

import com.syncspace.auth.User;
import com.syncspace.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted chat message. {@code workspaceId} is denormalized so
 * isolation checks never need a join through the channel. Deletes are
 * soft ({@code deletedAt}); soft-deleted messages are excluded from
 * history entirely, never returned with a deleted flag. The pair
 * (channel, clientMessageId) is unique: a re-sent client message returns
 * the existing row instead of a duplicate.
 */
@Entity
@Table(name = "messages",
        uniqueConstraints = @UniqueConstraint(name = "uq_message_channel_client_id",
                columnNames = {"channel_id", "client_message_id"}))
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(name = "client_message_id", nullable = false)
    private UUID clientMessageId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Message() {
    }

    public Message(Channel channel, Workspace workspace, User author,
                   UUID clientMessageId, String body) {
        this.channel = channel;
        this.workspace = workspace;
        this.author = author;
        this.clientMessageId = clientMessageId;
        this.body = body;
    }

    public UUID getId() {
        return id;
    }

    public Channel getChannel() {
        return channel;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public User getAuthor() {
        return author;
    }

    public UUID getClientMessageId() {
        return clientMessageId;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Instant getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(Instant editedAt) {
        this.editedAt = editedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
