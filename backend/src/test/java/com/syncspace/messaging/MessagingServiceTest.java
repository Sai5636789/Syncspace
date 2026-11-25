package com.syncspace.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.syncspace.auth.User;
import com.syncspace.common.ForbiddenException;
import com.syncspace.common.NotFoundException;
import com.syncspace.realtime.RealtimeEvent;
import com.syncspace.realtime.RealtimeEventPublisher;
import com.syncspace.workspace.Workspace;
import com.syncspace.workspace.WorkspaceMember;
import com.syncspace.workspace.WorkspaceMemberRepository;
import com.syncspace.workspace.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingServiceTest {

    @Mock
    private ChannelRepository channels;

    @Mock
    private MessageRepository messages;

    @Mock
    private ChannelReadRepository channelReads;

    @Mock
    private WorkspaceMemberRepository members;

    @Mock
    private RealtimeEventPublisher publisher;

    private MessagingService service;
    private User owner;
    private User member;
    private User viewer;
    private Workspace workspace;
    private Channel channel;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new MessagingService(channels, messages, channelReads, members, publisher, objectMapper);
        owner = user("owner@example.com");
        member = user("member@example.com");
        viewer = user("viewer@example.com");
        workspace = new Workspace("Team", "desc", owner);
        ReflectionTestUtils.setField(workspace, "id", UUID.randomUUID());
        channel = new Channel(workspace, "general", "General discussion", owner);
        ReflectionTestUtils.setField(channel, "id", UUID.randomUUID());
    }

    private User user(String email) {
        User user = new User(email, "Name", "hashed");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private void mockMembership(User user, WorkspaceRole role) {
        when(members.findByWorkspaceIdAndUserId(workspace.getId(), user.getId()))
                .thenReturn(Optional.of(new WorkspaceMember(workspace, user, role)));
    }

    private Message message(User author) {
        Message message = new Message(channel, workspace, author, UUID.randomUUID(), "hello");
        ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(message, "createdAt", Instant.now());
        ReflectionTestUtils.setField(message, "updatedAt", Instant.now());
        return message;
    }

    @Test
    void duplicateClientMessageIdReturnsExistingWithoutSaveOrBroadcast() {
        mockMembership(member, WorkspaceRole.MEMBER);
        when(channels.findById(channel.getId())).thenReturn(Optional.of(channel));
        UUID clientMessageId = UUID.randomUUID();
        Message existing = message(member);
        when(messages.findByChannelIdAndClientMessageId(channel.getId(), clientMessageId))
                .thenReturn(Optional.of(existing));

        MessagingService.SendResult result = service.sendMessage(
                member.getId(), workspace.getId(), channel.getId(), clientMessageId, "hello again");

        assertThat(result.duplicate()).isTrue();
        assertThat(result.message().id()).isEqualTo(existing.getId());
        verify(messages, never()).save(any());
        verify(publisher, never()).publishToWorkspaceTopic(any(), any());
    }

    @Test
    void freshSendPersistsAndPublishesMessageCreated() {
        mockMembership(member, WorkspaceRole.MEMBER);
        when(channels.findById(channel.getId())).thenReturn(Optional.of(channel));
        UUID clientMessageId = UUID.randomUUID();
        when(messages.findByChannelIdAndClientMessageId(channel.getId(), clientMessageId))
                .thenReturn(Optional.empty());
        when(messages.save(any(Message.class))).thenAnswer(inv -> {
            Message saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(saved, "createdAt", Instant.now());
            ReflectionTestUtils.setField(saved, "updatedAt", Instant.now());
            return saved;
        });

        MessagingService.SendResult result = service.sendMessage(
                member.getId(), workspace.getId(), channel.getId(), clientMessageId, "hello");

        assertThat(result.duplicate()).isFalse();
        assertThat(result.message().body()).isEqualTo("hello");
        ArgumentCaptor<RealtimeEvent> event = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(publisher).publishToWorkspaceTopic(event.capture(), eq("messages"));
        assertThat(event.getValue().eventType()).isEqualTo("MESSAGE_CREATED");
        assertThat(event.getValue().workspaceId()).isEqualTo(workspace.getId());
    }

    @Test
    void sendToChannelInAnotherWorkspaceIsNotFound() {
        UUID otherWorkspaceId = UUID.randomUUID();
        Workspace otherWorkspace = new Workspace("Other", null, owner);
        ReflectionTestUtils.setField(otherWorkspace, "id", otherWorkspaceId);
        when(members.findByWorkspaceIdAndUserId(otherWorkspaceId, member.getId()))
                .thenReturn(Optional.of(new WorkspaceMember(otherWorkspace, member, WorkspaceRole.MEMBER)));
        when(channels.findById(channel.getId())).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> service.sendMessage(
                member.getId(), otherWorkspaceId, channel.getId(), UUID.randomUUID(), "hi"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createDuplicateChannelNameIsConflict() {
        mockMembership(member, WorkspaceRole.MEMBER);
        when(channels.existsByWorkspaceIdAndName(workspace.getId(), "random")).thenReturn(true);

        assertThatThrownBy(() -> service.createChannel(member.getId(), workspace.getId(), "random", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createChannelAsViewerIsForbidden() {
        mockMembership(viewer, WorkspaceRole.VIEWER);

        assertThatThrownBy(() -> service.createChannel(viewer.getId(), workspace.getId(), "x", null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void editByNonAuthorIsForbidden() {
        Message existing = message(member);
        when(messages.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.editMessage(owner.getId(), existing.getId(), "edited"))
                .isInstanceOf(ForbiddenException.class);
        verify(publisher, never()).publishToWorkspaceTopic(any(), any());
    }

    @Test
    void editByAuthorSetsEditedAtAndPublishesMessageUpdated() {
        Message existing = message(member);
        when(messages.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(messages.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        MessagingService.MessageDto dto = service.editMessage(member.getId(), existing.getId(), "edited");

        assertThat(dto.body()).isEqualTo("edited");
        assertThat(dto.editedAt()).isNotNull();
        ArgumentCaptor<RealtimeEvent> event = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(publisher).publishToWorkspaceTopic(event.capture(), eq("messages"));
        assertThat(event.getValue().eventType()).isEqualTo("MESSAGE_UPDATED");
    }

    @Test
    void deleteByMemberNonAuthorIsForbidden() {
        Message existing = message(member);
        when(messages.findById(existing.getId())).thenReturn(Optional.of(existing));
        mockMembership(viewer, WorkspaceRole.MEMBER);

        assertThatThrownBy(() -> service.deleteMessage(viewer.getId(), existing.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void deleteByAdminPublishesMessageDeleted() {
        Message existing = message(member);
        when(messages.findById(existing.getId())).thenReturn(Optional.of(existing));
        mockMembership(owner, WorkspaceRole.ADMIN);
        when(messages.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deleteMessage(owner.getId(), existing.getId());

        assertThat(existing.getDeletedAt()).isNotNull();
        ArgumentCaptor<RealtimeEvent> event = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(publisher).publishToWorkspaceTopic(event.capture(), eq("messages"));
        assertThat(event.getValue().eventType()).isEqualTo("MESSAGE_DELETED");
    }

    @Test
    void unreadCountWithoutReadPointerCountsOthersMessages() {
        mockMembership(member, WorkspaceRole.MEMBER);
        when(channels.findByWorkspaceIdOrderByCreatedAtAsc(workspace.getId()))
                .thenReturn(List.of(channel));
        when(channelReads.findById(new ChannelRead.ChannelReadId(member.getId(), channel.getId())))
                .thenReturn(Optional.empty());
        when(messages.countByChannelIdAndAuthorIdNotAndDeletedAtIsNull(channel.getId(), member.getId()))
                .thenReturn(3L);

        List<MessagingService.ChannelDto> result =
                service.listChannels(member.getId(), workspace.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).unreadCount()).isEqualTo(3);
    }

    @Test
    void unreadCountWithReadPointerCountsOnlyNewerMessages() {
        mockMembership(member, WorkspaceRole.MEMBER);
        when(channels.findByWorkspaceIdOrderByCreatedAtAsc(workspace.getId()))
                .thenReturn(List.of(channel));
        Instant readAt = Instant.now().minusSeconds(3600);
        when(channelReads.findById(new ChannelRead.ChannelReadId(member.getId(), channel.getId())))
                .thenReturn(Optional.of(new ChannelRead(member.getId(), channel.getId(), readAt)));
        when(messages.countByChannelIdAndCreatedAtAfterAndAuthorIdNotAndDeletedAtIsNull(
                channel.getId(), readAt, member.getId())).thenReturn(1L);

        List<MessagingService.ChannelDto> result =
                service.listChannels(member.getId(), workspace.getId());

        assertThat(result.get(0).unreadCount()).isEqualTo(1);
    }

    @Test
    void listMessagesRejectsNegativePage() {
        assertThatThrownBy(() -> service.listMessages(member.getId(), channel.getId(), -1, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listMessagesReturnsChronologicalPage() {
        when(channels.findById(channel.getId())).thenReturn(Optional.of(channel));
        mockMembership(member, WorkspaceRole.MEMBER);
        Message first = message(member);
        Message second = message(owner);
        when(messages.findByChannelIdAndDeletedAtIsNull(eq(channel.getId()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 50), 2));

        MessagingService.PageDto<MessagingService.MessageDto> page =
                service.listMessages(member.getId(), channel.getId(), 0, 50);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).id()).isEqualTo(first.getId());
    }
}
