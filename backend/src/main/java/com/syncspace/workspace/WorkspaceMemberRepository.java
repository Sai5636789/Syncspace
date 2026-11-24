package com.syncspace.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    List<WorkspaceMember> findByWorkspaceId(UUID workspaceId);

    @Query("select m.workspace from WorkspaceMember m where m.user.id = :userId")
    List<Workspace> findWorkspacesByUserId(UUID userId);

    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);
}
