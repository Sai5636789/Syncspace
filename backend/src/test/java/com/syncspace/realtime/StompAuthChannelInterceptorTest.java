package com.syncspace.realtime;

import com.syncspace.auth.JwtService;
import com.syncspace.workspace.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

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
class StompAuthChannelInterceptorTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private WorkspaceMemberRepository members;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private StompAuthChannelInterceptor interceptor;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(jwtService, members, messagingTemplate);
    }

    private Message<byte[]> message(StompCommand command, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> connectMessage(String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("session-1");
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribeMessage(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("session-1");
        accessor.setUser(new StompPrincipal(userId));
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> appMessage() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionId("session-1");
        accessor.setUser(new StompPrincipal(userId));
        accessor.setDestination("/app/workspaces/" + workspaceId + "/chat");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void connectWithValidTokenBindsPrincipal() {
        when(jwtService.parseUserId("good-token")).thenReturn(Optional.of(userId));

        Message<?> result = interceptor.preSend(connectMessage("Bearer good-token"), null);

        assertThat(result).isNotNull();
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo(userId.toString());
    }

    @Test
    void connectWithSpaceAfterColonBindsPrincipal() {
        // Regression: the STOMP decoder keeps the header value verbatim, so a
        // client that writes "Authorization: Bearer <token>" (space after the
        // colon) arrives as " Bearer <token>". That must still authenticate.
        when(jwtService.parseUserId("good-token")).thenReturn(Optional.of(userId));

        Message<?> result = interceptor.preSend(connectMessage(" Bearer good-token"), null);

        assertThat(result).isNotNull();
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo(userId.toString());
    }

    @Test
    void connectWithoutAuthorizationIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(connectMessage(null), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void connectWithInvalidTokenIsRejected() {
        when(jwtService.parseUserId("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.preSend(connectMessage("Bearer bad-token"), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void memberSubscriptionIsAllowed() {
        when(members.existsByWorkspaceIdAndUserId(workspaceId, userId)).thenReturn(true);

        Message<?> result = interceptor.preSend(
                subscribeMessage("/topic/workspaces/" + workspaceId + "/messages"), null);

        assertThat(result).isNotNull();
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void nonMemberSubscriptionIsRefusedWithError() {
        when(members.existsByWorkspaceIdAndUserId(workspaceId, userId)).thenReturn(false);

        Message<?> result = interceptor.preSend(
                subscribeMessage("/topic/workspaces/" + workspaceId + "/messages"), null);

        assertThat(result).isNull();
        verify(messagingTemplate).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/errors"), any(RealtimeEvent.class));
    }

    @Test
    void malformedWorkspaceSubscriptionIsRefused() {
        Message<?> result = interceptor.preSend(
                subscribeMessage("/topic/workspaces/not-a-uuid/messages"), null);

        assertThat(result).isNull();
        verify(members, never()).existsByWorkspaceIdAndUserId(any(), any());
    }

    @Test
    void userQueueSubscriptionNeedsAuthOnly() {
        Message<?> result = interceptor.preSend(
                subscribeMessage("/user/queue/notifications"), null);

        assertThat(result).isNotNull();
        verify(members, never()).existsByWorkspaceIdAndUserId(any(), any());
    }

    @Test
    void moreThanSixtyMessagesPerMinuteAreDropped() {
        for (int i = 0; i < StompAuthChannelInterceptor.MAX_MESSAGES_PER_MINUTE; i++) {
            assertThat(interceptor.preSend(appMessage(), null)).isNotNull();
        }

        assertThat(interceptor.preSend(appMessage(), null)).isNull();
        verify(messagingTemplate).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/errors"), any(RealtimeEvent.class));
    }
}
