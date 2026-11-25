package com.syncspace.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelReadRepository extends JpaRepository<ChannelRead, ChannelRead.ChannelReadId> {
}
