package com.syncspace.workspace;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

/** Workspace endpoints. Membership and roles are enforced in WorkspaceService. */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceService.WorkspaceDto create(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody CreateWorkspaceRequest request) {
        return workspaceService.create(userId, request.name(), request.description());
    }

    @GetMapping
    public List<WorkspaceService.WorkspaceDto> listMine(@AuthenticationPrincipal UUID userId) {
        return workspaceService.listMine(userId);
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceService.WorkspaceDto get(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId) {
        return workspaceService.get(userId, workspaceId);
    }

    @PatchMapping("/{workspaceId}")
    public WorkspaceService.WorkspaceDto update(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateWorkspaceRequest request) {
        return workspaceService.update(userId, workspaceId, request.name(), request.description());
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId) {
        workspaceService.delete(userId, workspaceId);
    }

    @GetMapping("/{workspaceId}/members")
    public List<WorkspaceService.MemberDto> listMembers(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId) {
        return workspaceService.listMembers(userId, workspaceId);
    }

    @DeleteMapping("/{workspaceId}/members/{memberUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId,
            @PathVariable UUID memberUserId) {
        workspaceService.removeMember(userId, workspaceId, memberUserId);
    }

    @PostMapping("/{workspaceId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId) {
        workspaceService.leave(userId, workspaceId);
    }

    @PostMapping("/{workspaceId}/transfer")
    public WorkspaceService.WorkspaceDto transferOwnership(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody TransferOwnershipRequest request) {
        return workspaceService.transferOwnership(userId, workspaceId, request.newOwnerId());
    }

    @PostMapping("/{workspaceId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceService.InvitationDto invite(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody InviteRequest request) {
        return workspaceService.invite(userId, workspaceId, request.email(), request.role());
    }

    @PostMapping("/invitations/{token}/accept")
    public WorkspaceService.WorkspaceDto acceptInvitation(
            @AuthenticationPrincipal UUID userId,
            @PathVariable String token) {
        return workspaceService.acceptInvitation(userId, token);
    }

    public record CreateWorkspaceRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description) {
    }

    public record UpdateWorkspaceRequest(
            @Size(max = 120) String name,
            @Size(max = 2000) String description) {
    }

    public record TransferOwnershipRequest(@NotNull UUID newOwnerId) {
    }

    public record InviteRequest(
            @Email @NotBlank String email,
            @NotNull WorkspaceRole role) {
    }
}
