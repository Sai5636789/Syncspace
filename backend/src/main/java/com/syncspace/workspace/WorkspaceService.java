package com.syncspace.workspace;

import com.syncspace.auth.AuthService;
import com.syncspace.auth.User;
import com.syncspace.auth.UserRepository;
import com.syncspace.common.ForbiddenException;
import com.syncspace.common.InvalidCredentialsException;
import com.syncspace.common.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Workspace membership and authorization. Every operation first proves the
 * caller is a member; non-members get 404 (existence is not leaked) and
 * members without the required role get 403.
 */
@Service
public class WorkspaceService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final WorkspaceInvitationRepository invitations;
    private final UserRepository users;
    private final AuthService authService;
    private final long invitationExpiryHours;

    public WorkspaceService(
            WorkspaceRepository workspaces,
            WorkspaceMemberRepository members,
            WorkspaceInvitationRepository invitations,
            UserRepository users,
            AuthService authService,
            @Value("${syncspace.invitations.default-expiry-hours:72}") long invitationExpiryHours) {
        this.workspaces = workspaces;
        this.members = members;
        this.invitations = invitations;
        this.users = users;
        this.authService = authService;
        this.invitationExpiryHours = invitationExpiryHours;
    }

    @Transactional
    public WorkspaceDto create(UUID userId, String name, String description) {
        User owner = requireUser(userId);
        Workspace workspace = new Workspace(name.trim(), description == null ? null : description.trim(), owner);
        workspaces.save(workspace);
        members.save(new WorkspaceMember(workspace, owner, WorkspaceRole.OWNER));
        return toDto(workspace, WorkspaceRole.OWNER);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDto> listMine(UUID userId) {
        requireUser(userId);
        return members.findWorkspacesByUserId(userId).stream()
                .map(workspace -> {
                    WorkspaceRole role = members.findByWorkspaceIdAndUserId(workspace.getId(), userId)
                            .orElseThrow(() -> new NotFoundException("Workspace not found"))
                            .getRole();
                    return toDto(workspace, role);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceDto get(UUID userId, UUID workspaceId) {
        WorkspaceMember membership = requireMembership(workspaceId, userId);
        return toDto(membership.getWorkspace(), membership.getRole());
    }

    @Transactional
    public WorkspaceDto update(UUID userId, UUID workspaceId, String name, String description) {
        WorkspaceMember membership = requireRole(workspaceId, userId, WorkspaceRole.ADMIN);
        Workspace workspace = membership.getWorkspace();
        if (name != null) {
            workspace.setName(name.trim());
        }
        if (description != null) {
            workspace.setDescription(description.trim());
        }
        return toDto(workspaces.save(workspace), membership.getRole());
    }

    @Transactional
    public void delete(UUID userId, UUID workspaceId) {
        WorkspaceMember membership = requireRole(workspaceId, userId, WorkspaceRole.OWNER);
        // Member rows cascade from the workspace FK; the owner user is untouched.
        workspaces.delete(membership.getWorkspace());
    }

    @Transactional(readOnly = true)
    public List<MemberDto> listMembers(UUID userId, UUID workspaceId) {
        requireMembership(workspaceId, userId);
        return members.findByWorkspaceId(workspaceId).stream()
                .map(member -> new MemberDto(
                        member.getUser().getId(),
                        member.getUser().getEmail(),
                        member.getUser().getDisplayName(),
                        member.getRole(),
                        member.getJoinedAt()))
                .toList();
    }

    @Transactional
    public void removeMember(UUID userId, UUID workspaceId, UUID memberUserId) {
        WorkspaceMember caller = requireRole(workspaceId, userId, WorkspaceRole.ADMIN);
        WorkspaceMember target = members.findByWorkspaceIdAndUserId(workspaceId, memberUserId)
                .orElseThrow(() -> new NotFoundException("Member not found"));
        if (target.getRole() == WorkspaceRole.OWNER) {
            throw new IllegalStateException("The workspace owner cannot be removed; transfer ownership first");
        }
        if (caller.getRole() != WorkspaceRole.OWNER && target.getRole() == WorkspaceRole.ADMIN) {
            // An admin may only remove members/viewers, never another admin.
            throw new ForbiddenException("Admins cannot remove other admins");
        }
        if (target.getUser().getId().equals(userId)) {
            throw new IllegalStateException("Use leave to remove yourself from a workspace");
        }
        members.delete(target);
    }

    @Transactional
    public void leave(UUID userId, UUID workspaceId) {
        WorkspaceMember membership = requireMembership(workspaceId, userId);
        if (membership.getRole() == WorkspaceRole.OWNER) {
            throw new IllegalStateException(
                    "The workspace owner cannot leave; transfer ownership or delete the workspace");
        }
        members.delete(membership);
    }

    @Transactional
    public WorkspaceDto transferOwnership(UUID userId, UUID workspaceId, UUID newOwnerId) {
        WorkspaceMember caller = requireRole(workspaceId, userId, WorkspaceRole.OWNER);
        if (newOwnerId.equals(userId)) {
            throw new IllegalArgumentException("You already own this workspace");
        }
        WorkspaceMember target = members.findByWorkspaceIdAndUserId(workspaceId, newOwnerId)
                .orElseThrow(() -> new NotFoundException("New owner must be a member of the workspace"));
        Workspace workspace = caller.getWorkspace();
        workspace.setOwner(target.getUser());
        caller.setRole(WorkspaceRole.ADMIN);
        target.setRole(WorkspaceRole.OWNER);
        workspaces.save(workspace);
        members.save(caller);
        members.save(target);
        return toDto(workspace, WorkspaceRole.ADMIN);
    }

    @Transactional
    public InvitationDto invite(UUID userId, UUID workspaceId, String email, WorkspaceRole role) {
        WorkspaceMember caller = requireRole(workspaceId, userId, WorkspaceRole.ADMIN);
        if (role == WorkspaceRole.OWNER) {
            throw new IllegalArgumentException("Ownership cannot be granted by invitation; use transfer");
        }
        String normalizedEmail = email.trim().toLowerCase();
        users.findByEmail(normalizedEmail).ifPresent(existing -> {
            if (members.existsByWorkspaceIdAndUserId(workspaceId, existing.getId())) {
                throw new IllegalStateException("This person is already a member of the workspace");
            }
        });
        if (invitations.findByWorkspaceIdAndEmailAndAcceptedAtIsNull(workspaceId, normalizedEmail).isPresent()) {
            throw new IllegalStateException("An invitation for this email is already pending");
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        WorkspaceInvitation invitation = new WorkspaceInvitation(
                caller.getWorkspace(), caller.getUser(), normalizedEmail,
                sha256Hex(rawToken), role, Instant.now().plusSeconds(invitationExpiryHours * 3600));
        invitation.setRawTokenForResponse(rawToken);
        invitations.save(invitation);
        return toInvitationDto(invitation);
    }

    @Transactional
    public WorkspaceDto acceptInvitation(UUID userId, String rawToken) {
        User user = requireUser(userId);
        WorkspaceInvitation invitation = invitations.findByTokenHash(sha256Hex(rawToken))
                .orElseThrow(() -> new InvalidCredentialsException("Invalid invitation"));
        if (!invitation.isUsable()) {
            throw new IllegalStateException("This invitation has expired or was already used");
        }
        if (!invitation.getEmail().equalsIgnoreCase(user.getEmail())) {
            throw new ForbiddenException("This invitation was sent to a different email address");
        }
        UUID workspaceId = invitation.getWorkspace().getId();
        if (members.existsByWorkspaceIdAndUserId(workspaceId, userId)) {
            throw new IllegalStateException("You are already a member of this workspace");
        }
        invitation.markAccepted();
        invitations.save(invitation);
        members.save(new WorkspaceMember(invitation.getWorkspace(), user, invitation.getRole()));
        return toDto(invitation.getWorkspace(), invitation.getRole());
    }

    // -- helpers -----------------------------------------------------------

    private WorkspaceMember requireMembership(UUID workspaceId, UUID userId) {
        requireUser(userId);
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

    private User requireUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    private WorkspaceDto toDto(Workspace workspace, WorkspaceRole role) {
        return new WorkspaceDto(
                workspace.getId(), workspace.getName(), workspace.getDescription(),
                workspace.getOwner().getId(), role,
                workspace.getCreatedAt(), workspace.getUpdatedAt());
    }

    private InvitationDto toInvitationDto(WorkspaceInvitation invitation) {
        return new InvitationDto(
                invitation.getId(), invitation.getWorkspace().getId(), invitation.getEmail(),
                invitation.getRole(), invitation.getExpiresAt(),
                invitation.getRawTokenForResponse());
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record WorkspaceDto(UUID id, String name, String description, UUID ownerId,
                               WorkspaceRole role, Instant createdAt, Instant updatedAt) {
    }

    public record MemberDto(UUID userId, String email, String displayName,
                            WorkspaceRole role, Instant joinedAt) {
    }

    public record InvitationDto(UUID id, UUID workspaceId, String email,
                                WorkspaceRole role, Instant expiresAt, String token) {
    }
}
