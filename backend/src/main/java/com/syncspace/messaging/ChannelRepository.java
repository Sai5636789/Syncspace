package com.syncspace.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelRepository extends JpaRepository<Channel, UUID> {

    List<Channel> findByWorkspaceIdOrderByCreatedAtAsc(UUID workspaceId);

    Optional<Channel> findByWorkspaceIdAndName(UUID workspaceId, String name);

    boolean existsByWorkspaceIdAndName(UUID workspaceId, String name);
}
