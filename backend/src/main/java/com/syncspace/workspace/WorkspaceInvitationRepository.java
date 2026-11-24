package com.syncspace.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, UUID> {

    Optional<WorkspaceInvitation> findByTokenHash(String tokenHash);

    Optional<WorkspaceInvitation> findByWorkspaceIdAndEmailAndAcceptedAtIsNull(UUID workspaceId, String email);
}
