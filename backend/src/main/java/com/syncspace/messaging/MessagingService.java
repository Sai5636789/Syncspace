package com.syncspace.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncspace.auth.User;
import com.syncspace.common.ForbiddenException;
import com.syncspace.common.NotFoundException;
import com.syncspace.realtime.RealtimeEvent;
import com.syncspace.realtime.RealtimeEventPublisher;
import com.syncspace.workspace.WorkspaceMember;
import com.syncspace.workspace.WorkspaceMemberRepository;
import com.syncspace.workspace.WorkspaceRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Channels and messages. Every operation first proves the caller is a
 * member of the owning workspace; non-members get 404 (existence is not
 * leaked) and members without the required role get 403.
 *
 * <p>Messages are persisted in PostgreSQL before any broadcast, and the
 * broadcast happens only after the transaction commits (see
 * {@link #publishAfterCommit}). A re-sent {@code clientMessageId} returns
 * the existing message instead of a duplicate row or a second broadcast;
 * a truly concurrent duplicate can surface as a 409, and retrying the
 * send then returns the existing message. Soft-deleted messages are
 * excluded from history entirely, never returned with a deleted flag.
 * History pages are chronological (page 0 holds the oldest messages).
 */
@Service
public class MessagingService {

    /** Maximum message body length, enforced by Bean Validation as well. */
    public static final int MAX_BODY_LENGTH = 10_000;

    /** Maximum page size for message history; larger sizes are clamped. */
    public static final int MAX_PAGE_SIZE = 100;

    private final ChannelRepository channels;
    private final MessageRepository messages;
    private final ChannelReadRepository channelReads;
    private final WorkspaceMemberRepository members;
    private final RealtimeEventPublisher publisher;
    private final ObjectMapper objectMapper;

    public MessagingService(
            ChannelRepository channels,
            MessageRepository messages,
            ChannelReadRepository channelReads,
            WorkspaceMemberRepository members,
            RealtimeEventPublisher publisher,
            ObjectMapper objectMapper) {
        this.channels = channels;
        this.messages = messages;
        this.channelReads = channelReads;
        this.members = members;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    // -- channels ----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ChannelDto> listChannels(UUID userId, UUID workspaceId) {
        requireMembership(workspaceId, userId);
        return channels.findByWorkspaceIdOrderByCreatedAtAsc(workspaceId).stream()
                .map(channel -> toChannelDto(channel, unreadCount(channel.getId(), userId)))
                .toList();
    }

    @Transactional
    public ChannelDto createChannel(UUID userId, UUID workspaceId, String name, String description) {
        WorkspaceMember membership = requireRole(workspaceId, userId, WorkspaceRole.MEMBER);
        String trimmedName = name.trim();
        if (channels.existsByWorkspaceIdAndName(workspaceId, trimmedName)) {
            throw new IllegalStateException("A channel with this name already exists");
        }
        try {
            Channel channel = channels.save(new Channel(
                    membership.getWorkspace(), trimmedName,
                    description == null ? null : description.trim(), membership.getUser()));
            return toChannelDto(channel, 0);
        } catch (DataIntegrityViolationException ex) {
            // Lost a race with a concurrent create of the same name.
            throw new IllegalStateException("A channel with this name already exists");
        }
    }

    @Transactional
    public ChannelReadDto markRead(UUID userId, UUID channelId) {
        Channel channel = requireChannel(channelId);
        requireMembership(channel.getWorkspace().getId(), userId);
        Instant now = Instant.now();
        ChannelRead read = channelReads.findById(new ChannelRead.ChannelReadId(userId, channelId))
                .orElseGet(() -> new ChannelRead(userId, channelId, now));
        read.setLastReadAt(now);
        channelReads.save(read);
        return new ChannelReadDto(channelId, now);
    }

    // -- messages ----------------------------------------------------------

    @Transactional(readOnly = true)
    public PageDto<MessageDto> listMessages(UUID userId, UUID channelId, int page, int size) {
        if (page < 0 || size < 1) {
            throw new IllegalArgumentException("page must be >= 0 and size must be >= 1");
        }
        Channel channel = requireChannel(channelId);
        requireMembership(channel.getWorkspace().getId(), userId);
        Page<Message> result = messages.findByChannelIdAndDeletedAtIsNull(
                channelId, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.ASC, "createdAt")));
        return new PageDto<>(
                result.getContent().stream().map(this::toDto).toList(),
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public SendResult sendMessage(UUID userId, UUID workspaceId, UUID channelId,
                                  UUID clientMessageId, String body) {
        WorkspaceMember membership = requireMembership(workspaceId, userId);
        Channel channel = requireChannelInWorkspace(channelId, workspaceId);
        return messages.findByChannelIdAndClientMessageId(channelId, clientMessageId)
                .map(existing -> new SendResult(toDto(existing), true))
                .orElseGet(() -> {
                    Message message = messages.save(new Message(
                            channel, membership.getWorkspace(), membership.getUser(),
                            clientMessageId, body.trim()));
                    MessageDto dto = toDto(message);
                    publishAfterCommit(RealtimeEvent.create("MESSAGE_CREATED", workspaceId,
                            message.getId(), userId, 1,
                            Map.of("channelId", channelId.toString(), "message", toPayloadMap(dto))),
                            "messages");
                    return new SendResult(dto, false);
                });
    }

    @Transactional
    public MessageDto editMessage(UUID userId, UUID messageId, String body) {
        Message message = requireLiveMessage(messageId);
        if (message.getAuthor() == null || !message.getAuthor().getId().equals(userId)) {
            throw new ForbiddenException("Only the author can edit this message");
        }
        message.setBody(body.trim());
        message.setEditedAt(Instant.now());
        Message saved = messages.save(message);
        MessageDto dto = toDto(saved);
        publishAfterCommit(RealtimeEvent.create("MESSAGE_UPDATED",
                message.getWorkspace().getId(), message.getId(), userId, 1,
                Map.of("channelId", message.getChannel().getId().toString(),
                        "message", toPayloadMap(dto))),
                "messages");
        return dto;
    }

    @Transactional
    public void deleteMessage(UUID userId, UUID messageId) {
        Message message = requireLiveMessage(messageId);
        WorkspaceMember membership = requireMembership(message.getWorkspace().getId(), userId);
        boolean isAuthor = message.getAuthor() != null
                && message.getAuthor().getId().equals(userId);
        if (!isAuthor && !membership.getRole().atLeast(WorkspaceRole.ADMIN)) {
            throw new ForbiddenException("Only the author or an admin can delete this message");
        }
        message.setDeletedAt(Instant.now());
        messages.save(message);
        publishAfterCommit(RealtimeEvent.create("MESSAGE_DELETED",
                message.getWorkspace().getId(), message.getId(), userId, 1,
                Map.of("channelId", message.getChannel().getId().toString(),
                        "messageId", message.getId().toString())),
                "messages");
    }

    // -- typing / acks (ephemeral, never persisted) -------------------------

    /**
     * Validates a typing signal and broadcasts it. Typing state is
     * ephemeral: nothing is written to PostgreSQL.
     */
    @Transactional(readOnly = true)
    public void broadcastTyping(UUID userId, UUID workspaceId, UUID channelId, boolean typing) {
        WorkspaceMember membership = requireMembership(workspaceId, userId);
        requireChannelInWorkspace(channelId, workspaceId);
        User user = membership.getUser();
        publisher.publishToWorkspaceTopic(RealtimeEvent.create(
                typing ? "TYPING_STARTED" : "TYPING_STOPPED", workspaceId, channelId, userId, 1,
                Map.of("channelId", channelId.toString(),
                        "userId", userId.toString(),
                        "displayName", user.getDisplayName())),
                "typing");
    }

    /**
     * Best-effort delivery acknowledgement: validates the message and the
     * caller's membership, then confirms receipt to the caller's own
     * acknowledgement queue. Nothing is persisted.
     */
    @Transactional(readOnly = true)
    public void acknowledge(UUID userId, UUID workspaceId, UUID messageId) {
        Message message = messages.findById(messageId)
                .orElseThrow(() -> new NotFoundException("Message not found"));
        if (!message.getWorkspace().getId().equals(workspaceId)) {
            throw new NotFoundException("Message not found");
        }
        requireMembership(workspaceId, userId);
        publisher.publishToUser(userId, RealtimeEvent.create(
                "MESSAGE_ACKNOWLEDGED", workspaceId, messageId, userId, 1,
                Map.of("messageId", messageId.toString(), "status", "ACKED")),
                "/queue/acknowledgements");
    }

    // -- helpers -----------------------------------------------------------

    private long unreadCount(UUID channelId, UUID userId) {
        return channelReads.findById(new ChannelRead.ChannelReadId(userId, channelId))
                .map(read -> messages.countByChannelIdAndCreatedAtAfterAndAuthorIdNotAndDeletedAtIsNull(
                        channelId, read.getLastReadAt(), userId))
                .orElseGet(() -> messages.countByChannelIdAndAuthorIdNotAndDeletedAtIsNull(
                        channelId, userId));
    }

    private WorkspaceMember requireMembership(UUID workspaceId, UUID userId) {
        return members.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new NotFoundException("Workspace not found"));
    }

    private WorkspaceMember requireRole(UUID workspaceId, UUID userId, WorkspaceRole minimum) {
        WorkspaceMember membership = requireMembership(workspaceId, userId);
        if (!membership.getRole().atLeast(minimum)) {
            throw new ForbiddenException("This action requires the " + minimum + " role or higher");
        }
        return membership;
    }

    private Channel requireChannel(UUID channelId) {
        return channels.findById(channelId)
                .orElseThrow(() -> new NotFoundException("Channel not found"));
    }

    private Channel requireChannelInWorkspace(UUID channelId, UUID workspaceId) {
        Channel channel = requireChannel(channelId);
        if (!channel.getWorkspace().getId().equals(workspaceId)) {
            throw new NotFoundException("Channel not found");
        }
        return channel;
    }

    private Message requireLiveMessage(UUID messageId) {
        Message message = messages.findById(messageId)
                .orElseThrow(() -> new NotFoundException("Message not found"));
        if (message.getDeletedAt() != null) {
            throw new NotFoundException("Message not found");
        }
        return message;
    }

    /**
     * Broadcasts only after the surrounding transaction commits, so clients
     * never receive an event for a row that rolled back. With no active
     * transaction (e.g. a direct service call in a unit test) the event is
     * published immediately — there is nothing to wait for.
     */
    private void publishAfterCommit(RealtimeEvent event, String topicSuffix) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publishToWorkspaceTopic(event, topicSuffix);
                }
            });
        } else {
            publisher.publishToWorkspaceTopic(event, topicSuffix);
        }
    }

    private Map<String, Object> toPayloadMap(MessageDto dto) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = objectMapper.convertValue(dto, Map.class);
        return map;
    }

    private ChannelDto toChannelDto(Channel channel, long unreadCount) {
        return new ChannelDto(
                channel.getId(), channel.getWorkspace().getId(), channel.getName(),
                channel.getDescription(),
                channel.getCreatedBy() == null ? null : channel.getCreatedBy().getId(),
                unreadCount, channel.getCreatedAt(), channel.getUpdatedAt());
    }

    private MessageDto toDto(Message message) {
        return new MessageDto(
                message.getId(), message.getChannel().getId(), message.getWorkspace().getId(),
                message.getAuthor() == null ? null : message.getAuthor().getId(),
                message.getAuthor() == null ? null : message.getAuthor().getDisplayName(),
                message.getClientMessageId(), message.getBody(),
                message.getEditedAt(), message.getDeletedAt(),
                message.getCreatedAt(), message.getUpdatedAt());
    }

    public record ChannelDto(UUID id, UUID workspaceId, String name, String description,
                             UUID createdById, long unreadCount,
                             Instant createdAt, Instant updatedAt) {
    }

    public record ChannelReadDto(UUID channelId, Instant lastReadAt) {
    }

    public record MessageDto(UUID id, UUID channelId, UUID workspaceId,
                             UUID authorId, String authorDisplayName,
                             UUID clientMessageId, String body,
                             Instant editedAt, Instant deletedAt,
                             Instant createdAt, Instant updatedAt) {
    }

    public record PageDto<T>(List<T> content, int page, int size,
                             long totalElements, int totalPages) {
    }

    /** A send outcome: the message, and whether it was a duplicate resend. */
    public record SendResult(MessageDto message, boolean duplicate) {
    }
}
