package com.syncspace.messaging;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    Optional<Message> findByChannelIdAndClientMessageId(UUID channelId, UUID clientMessageId);

    /** Live (non-deleted) messages in a channel, paged. */
    Page<Message> findByChannelIdAndDeletedAtIsNull(UUID channelId, Pageable pageable);

    long countByChannelIdAndAuthorIdNotAndDeletedAtIsNull(UUID channelId, UUID authorId);

    long countByChannelIdAndCreatedAtAfterAndAuthorIdNotAndDeletedAtIsNull(
            UUID channelId, Instant createdAfter, UUID authorId);
}
