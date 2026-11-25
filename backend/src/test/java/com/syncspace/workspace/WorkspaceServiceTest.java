package com.syncspace.workspace;

import com.syncspace.auth.AuthService;
import com.syncspace.auth.User;
import com.syncspace.auth.UserRepository;
import com.syncspace.common.ForbiddenException;
import com.syncspace.common.NotFoundException;
import com.syncspace.messaging.ChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private WorkspaceRepository workspaces;

    @Mock
    private WorkspaceMemberRepository members;

    @Mock
    private WorkspaceInvitationRepository invitations;

    @Mock
    private UserRepository users;

    @Mock
    private AuthService authService;

    @Mock
    private ChannelRepository channels;

    private WorkspaceService service;
    private User owner;
    private User member;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new WorkspaceService(workspaces, members, invitations, users, authService, channels, 72);
        owner = user("owner@example.com");
        member = user("member@example.com");
        workspace = new Workspace("Team", "desc", owner);
        ReflectionTestUtils.setField(workspace, "id", UUID.randomUUID());
    }

    private User user(String email) {
        User user = new User(email, "Name", "hashed");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private WorkspaceMember membership(User user, WorkspaceRole role) {
        return new WorkspaceMember(workspace, user, role);
    }

    private void mockMembership(User user, WorkspaceRole role) {
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(members.findByWorkspaceIdAndUserId(workspace.getId(), user.getId()))
                .thenReturn(Optional.of(membership(user, role)));
    }

    @Test
    void createMakesCallerOwner() {
        when(users.findById(owner.getId())).thenReturn(Optional.of(owner));

        WorkspaceService.WorkspaceDto dto = service.create(owner.getId(), "Team", "desc");

        assertThat(dto.role()).isEqualTo(WorkspaceRole.OWNER);
        verify(members).save(any(WorkspaceMember.class));
        verify(channels).save(any(com.syncspace.messaging.Channel.class));
    }

    @Test
    void createSeedsGeneralChannel() {
        when(users.findById(owner.getId())).thenReturn(Optional.of(owner));

        service.create(owner.getId(), "Team", "desc");

        org.mockito.ArgumentCaptor<com.syncspace.messaging.Channel> channel =
                org.mockito.ArgumentCaptor.forClass(com.syncspace.messaging.Channel.class);
        verify(channels).save(channel.capture());
        assertThat(channel.getValue().getName()).isEqualTo("general");
        assertThat(channel.getValue().getWorkspace().getName()).isEqualTo("Team");
        assertThat(channel.getValue().getWorkspace().getOwner()).isEqualTo(owner);
    }

    @Test
    void getAsNonMemberIsNotFound() {
        when(users.findById(member.getId())).thenReturn(Optional.of(member));
        when(members.findByWorkspaceIdAndUserId(workspace.getId(), member.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(member.getId(), workspace.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateAsViewerIsForbidden() {
        mockMembership(member, WorkspaceRole.VIEWER);

        assertThatThrownBy(() -> service.update(member.getId(), workspace.getId(), "New", null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void updateAsAdminSucceeds() {
        mockMembership(member, WorkspaceRole.ADMIN);
        when(workspaces.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceService.WorkspaceDto dto =
                service.update(member.getId(), workspace.getId(), "New name", null);

        assertThat(dto.name()).isEqualTo("New name");
    }

    @Test
    void deleteAsMemberIsForbidden() {
        mockMembership(member, WorkspaceRole.MEMBER);

        assertThatThrownBy(() -> service.delete(member.getId(), workspace.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void ownerCannotLeave() {
        mockMembership(owner, WorkspaceRole.OWNER);

        assertThatThrownBy(() -> service.leave(owner.getId(), workspace.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void inviteAsMemberIsForbidden() {
        mockMembership(member, WorkspaceRole.MEMBER);

        assertThatThrownBy(() -> service.invite(member.getId(), workspace.getId(), "x@example.com", WorkspaceRole.MEMBER))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void inviteCannotGrantOwnerRole() {
        mockMembership(owner, WorkspaceRole.OWNER);

        assertThatThrownBy(() -> service.invite(owner.getId(), workspace.getId(), "x@example.com", WorkspaceRole.OWNER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inviteReturnsSingleUseToken() {
        mockMembership(owner, WorkspaceRole.OWNER);
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(invitations.findByWorkspaceIdAndEmailAndAcceptedAtIsNull(workspace.getId(), "new@example.com"))
                .thenReturn(Optional.empty());
        when(invitations.save(any(WorkspaceInvitation.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceService.InvitationDto dto =
                service.invite(owner.getId(), workspace.getId(), "new@example.com", WorkspaceRole.MEMBER);

        assertThat(dto.token()).isNotBlank();
        assertThat(dto.role()).isEqualTo(WorkspaceRole.MEMBER);
        verify(invitations).save(any(WorkspaceInvitation.class));
    }

    @Test
    void acceptInvitationWithWrongEmailIsForbidden() {
        User invited = user("invited@example.com");
        String rawToken = "raw-invitation-token";
        WorkspaceInvitation invitation = new WorkspaceInvitation(
                workspace, owner, "invited@example.com",
                WorkspaceService.sha256Hex(rawToken), WorkspaceRole.MEMBER,
                Instant.now().plusSeconds(3600));
        when(users.findById(member.getId())).thenReturn(Optional.of(member));
        when(invitations.findByTokenHash(WorkspaceService.sha256Hex(rawToken)))
                .thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.acceptInvitation(member.getId(), rawToken))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void acceptExpiredInvitationFails() {
        String rawToken = "expired-token";
        WorkspaceInvitation invitation = new WorkspaceInvitation(
                workspace, owner, "member@example.com",
                WorkspaceService.sha256Hex(rawToken), WorkspaceRole.MEMBER,
                Instant.now().minusSeconds(60));
        when(users.findById(member.getId())).thenReturn(Optional.of(member));
        when(invitations.findByTokenHash(WorkspaceService.sha256Hex(rawToken)))
                .thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.acceptInvitation(member.getId(), rawToken))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transferOwnershipToNonMemberFails() {
        mockMembership(owner, WorkspaceRole.OWNER);
        when(members.findByWorkspaceIdAndUserId(workspace.getId(), member.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transferOwnership(owner.getId(), workspace.getId(), member.getId()))
                .isInstanceOf(NotFoundException.class);
    }
}
