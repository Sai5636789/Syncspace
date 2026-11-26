package com.syncspace.presence;

import com.syncspace.auth.User;
import com.syncspace.common.NotFoundException;
import com.syncspace.realtime.RealtimeEvent;
import com.syncspace.realtime.RealtimeEventPublisher;
import com.syncspace.workspace.WorkspaceMember;
import com.syncspace.workspace.WorkspaceMemberRepository;
import com.syncspace.workspace.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Presence rules against mocked Redis: membership gating, broadcast only
 * on transitions, per-session keys so one tab closing does not take the
 * user offline, and the TTL-expiry sweep.
 */
@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final UUID WORKSPACE_ID = UUID.randomUUID();

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private HashOperations<String, Object, Object> hashOps;
    @Mock
    private WorkspaceMemberRepository members;
    @Mock
    private RealtimeEventPublisher publisher;
    @Mock
    private WorkspaceMember membership;
    @Mock
    private User user;

    private PresenceService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
        service = new PresenceService(redis, members, publisher, 45);
    }

    private void memberExists() {
        when(members.findByWorkspaceIdAndUserId(WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.of(membership));
    }

    @Test
    void heartbeatRejectsNonMemberWithoutTouchingRedis() {
        when(members.findByWorkspaceIdAndUserId(WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.heartbeat(USER_ID, WORKSPACE_ID, "s-1"))
                .isInstanceOf(NotFoundException.class);
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
        verify(publisher, never()).publishToWorkspaceTopic(any(), any());
    }

    @Test
    void heartbeatWritesSessionKeyWithTtlAndBroadcastsOnlineOnlyOnTransition() {
        memberExists();
        // First heartbeat: no previous status → online broadcast.
        // Second heartbeat: already online → no second broadcast.
        when(hashOps.get(PresenceService.statusKey(WORKSPACE_ID), USER_ID.toString()))
                .thenReturn(null, "online");

        assertThat(service.heartbeat(USER_ID, WORKSPACE_ID, "s-1")).isTrue();
        assertThat(service.heartbeat(USER_ID, WORKSPACE_ID, "s-1")).isFalse();

        verify(valueOps, times(2)).set(
                eq(PresenceService.sessionKey(WORKSPACE_ID, USER_ID, "s-1")),
                anyString(), eq(Duration.ofSeconds(45)));
        verify(setOps, times(2)).add(PresenceService.userSessionsKey(WORKSPACE_ID, USER_ID), "s-1");
        ArgumentCaptor<RealtimeEvent> event = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(publisher).publishToWorkspaceTopic(event.capture(), eq("presence"));
        assertThat(event.getValue().eventType()).isEqualTo("PRESENCE_CHANGED");
        assertThat(event.getValue().workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(event.getValue().payload())
                .containsEntry("userId", USER_ID.toString())
                .containsEntry("status", "online");
        assertThat(event.getValue().payload()).containsKey("lastSeenAt");
    }

    @Test
    void listPresenceReportsLiveSessionsAndLastSeen() {
        memberExists();
        WorkspaceMember other = org.mockito.Mockito.mock(WorkspaceMember.class);
        User otherUser = org.mockito.Mockito.mock(User.class);
        when(members.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(membership, other));
        when(membership.getUser()).thenReturn(user);
        when(membership.getRole()).thenReturn(WorkspaceRole.OWNER);
        when(membership.getJoinedAt()).thenReturn(Instant.parse("2026-11-26T00:00:00Z"));
        when(user.getId()).thenReturn(USER_ID);
        when(user.getEmail()).thenReturn("owner@example.com");
        when(user.getDisplayName()).thenReturn("Owner");
        when(other.getUser()).thenReturn(otherUser);
        when(other.getRole()).thenReturn(WorkspaceRole.MEMBER);
        when(other.getJoinedAt()).thenReturn(Instant.parse("2026-11-26T00:01:00Z"));
        when(otherUser.getId()).thenReturn(OTHER_ID);
        when(otherUser.getEmail()).thenReturn("member@example.com");
        when(otherUser.getDisplayName()).thenReturn("Member");

        when(setOps.members(PresenceService.userSessionsKey(WORKSPACE_ID, USER_ID)))
                .thenReturn(Set.of("s-1"));
        when(setOps.members(PresenceService.userSessionsKey(WORKSPACE_ID, OTHER_ID)))
                .thenReturn(Set.of("s-9"));
        when(redis.hasKey(PresenceService.sessionKey(WORKSPACE_ID, USER_ID, "s-1")))
                .thenReturn(true);
        when(redis.hasKey(PresenceService.sessionKey(WORKSPACE_ID, OTHER_ID, "s-9")))
                .thenReturn(false);
        when(hashOps.get(PresenceService.lastSeenKey(WORKSPACE_ID), USER_ID.toString()))
                .thenReturn("2026-11-26T10:00:00Z");
        when(hashOps.get(PresenceService.lastSeenKey(WORKSPACE_ID), OTHER_ID.toString()))
                .thenReturn("2026-11-26T09:00:00Z");

        List<PresenceService.PresenceMemberDto> result =
                service.listPresence(USER_ID, WORKSPACE_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).status()).isEqualTo("online");
        assertThat(result.get(0).lastSeenAt()).isEqualTo(Instant.parse("2026-11-26T10:00:00Z"));
        assertThat(result.get(1).status()).isEqualTo("offline");
        assertThat(result.get(1).lastSeenAt()).isEqualTo(Instant.parse("2026-11-26T09:00:00Z"));
        // The dead session id is pruned from the set when liveness is checked.
        verify(setOps).remove(PresenceService.userSessionsKey(WORKSPACE_ID, OTHER_ID), "s-9");
    }

    @Test
    void listPresenceRejectsNonMember() {
        when(members.findByWorkspaceIdAndUserId(WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listPresence(USER_ID, WORKSPACE_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void disconnectOfOneTabKeepsUserOnlineWhenAnotherTabRemains() {
        when(setOps.members(PresenceService.sessionWorkspacesKey("s-1")))
                .thenReturn(Set.of(WORKSPACE_ID.toString()));
        when(setOps.members(PresenceService.userSessionsKey(WORKSPACE_ID, USER_ID)))
                .thenReturn(Set.of("s-2"));
        when(redis.hasKey(PresenceService.sessionKey(WORKSPACE_ID, USER_ID, "s-2")))
                .thenReturn(true);

        service.handleDisconnect("s-1", USER_ID);

        verify(redis).delete(PresenceService.sessionKey(WORKSPACE_ID, USER_ID, "s-1"));
        verify(publisher, never()).publishToWorkspaceTopic(any(), any());
    }

    @Test
    void disconnectOfLastSessionBroadcastsOffline() {
        when(setOps.members(PresenceService.sessionWorkspacesKey("s-1")))
                .thenReturn(Set.of(WORKSPACE_ID.toString()));
        when(setOps.members(PresenceService.userSessionsKey(WORKSPACE_ID, USER_ID)))
                .thenReturn(Set.of());
        when(hashOps.get(PresenceService.lastSeenKey(WORKSPACE_ID), USER_ID.toString()))
                .thenReturn("2026-11-26T10:00:00Z");

        service.handleDisconnect("s-1", USER_ID);

        ArgumentCaptor<RealtimeEvent> event = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(publisher).publishToWorkspaceTopic(event.capture(), eq("presence"));
        assertThat(event.getValue().payload())
                .containsEntry("status", "offline")
                .containsEntry("userId", USER_ID.toString())
                .containsEntry("lastSeenAt", "2026-11-26T10:00:00Z");
        verify(hashOps).put(PresenceService.statusKey(WORKSPACE_ID), USER_ID.toString(), "offline");
    }

    @Test
    void sweepBroadcastsOfflineForExpiredOnlineUsersAndSkipsAlreadyOffline() {
        when(setOps.members(PresenceService.workspacesKey()))
                .thenReturn(Set.of(WORKSPACE_ID.toString()));
        when(setOps.members(PresenceService.usersKey(WORKSPACE_ID)))
                .thenReturn(Set.of(USER_ID.toString(), OTHER_ID.toString()));
        // Neither user holds a live session key.
        when(setOps.members(PresenceService.userSessionsKey(WORKSPACE_ID, USER_ID)))
                .thenReturn(Set.of("s-1"));
        when(setOps.members(PresenceService.userSessionsKey(WORKSPACE_ID, OTHER_ID)))
                .thenReturn(Set.of("s-9"));
        when(redis.hasKey(any())).thenReturn(false);
        when(hashOps.get(PresenceService.statusKey(WORKSPACE_ID), USER_ID.toString()))
                .thenReturn("online");
        when(hashOps.get(PresenceService.statusKey(WORKSPACE_ID), OTHER_ID.toString()))
                .thenReturn("offline");
        when(hashOps.get(PresenceService.lastSeenKey(WORKSPACE_ID), USER_ID.toString()))
                .thenReturn("2026-11-26T10:00:00Z");

        service.sweepExpired();

        ArgumentCaptor<RealtimeEvent> event = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(publisher).publishToWorkspaceTopic(event.capture(), eq("presence"));
        assertThat(event.getValue().payload())
                .containsEntry("userId", USER_ID.toString())
                .containsEntry("status", "offline");
    }

    @Test
    void sweepSelfHealsLiveUserWithStaleOfflineStatus() {
        when(setOps.members(PresenceService.workspacesKey()))
                .thenReturn(Set.of(WORKSPACE_ID.toString()));
        when(setOps.members(PresenceService.usersKey(WORKSPACE_ID)))
                .thenReturn(Set.of(USER_ID.toString()));
        when(setOps.members(PresenceService.userSessionsKey(WORKSPACE_ID, USER_ID)))
                .thenReturn(Set.of("s-1"));
        when(redis.hasKey(PresenceService.sessionKey(WORKSPACE_ID, USER_ID, "s-1")))
                .thenReturn(true);
        when(hashOps.get(PresenceService.statusKey(WORKSPACE_ID), USER_ID.toString()))
                .thenReturn("offline");
        when(hashOps.get(PresenceService.lastSeenKey(WORKSPACE_ID), USER_ID.toString()))
                .thenReturn("2026-11-26T10:00:00Z");

        service.sweepExpired();

        ArgumentCaptor<RealtimeEvent> event = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(publisher).publishToWorkspaceTopic(event.capture(), eq("presence"));
        assertThat(event.getValue().payload()).containsEntry("status", "online");
    }
}
