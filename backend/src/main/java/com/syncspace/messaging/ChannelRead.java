package com.syncspace.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-user read pointer for a channel. {@code lastReadAt} is the instant
 * the user last marked the channel read; messages created after it (and
 * not authored by the user) count as unread.
 */
@Entity
@Table(name = "channel_reads")
public class ChannelRead {

    @EmbeddedId
    private ChannelReadId id;

    @Column(name = "last_read_at", nullable = false)
    private Instant lastReadAt;

    protected ChannelRead() {
    }

    public ChannelRead(UUID userId, UUID channelId, Instant lastReadAt) {
        this.id = new ChannelReadId(userId, channelId);
        this.lastReadAt = lastReadAt;
    }

    public ChannelReadId getId() {
        return id;
    }

    public UUID getUserId() {
        return id.userId();
    }

    public UUID getChannelId() {
        return id.channelId();
    }

    public Instant getLastReadAt() {
        return lastReadAt;
    }

    public void setLastReadAt(Instant lastReadAt) {
        this.lastReadAt = lastReadAt;
    }

    @Embeddable
    public record ChannelReadId(
            @Column(name = "user_id") UUID userId,
            @Column(name = "channel_id") UUID channelId) implements Serializable {
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return Objects.equals(id, ((ChannelRead) other).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
