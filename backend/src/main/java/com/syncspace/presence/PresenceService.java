package com.syncspace.presence;

import com.syncspace.common.NotFoundException;
import com.syncspace.realtime.RealtimeEvent;
import com.syncspace.realtime.RealtimeEventPublisher;
import com.syncspace.workspace.WorkspaceMember;
import com.syncspace.workspace.WorkspaceMemberRepository;
import com.syncspace.workspace.WorkspaceRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Workspace presence, backed by Redis. Presence is real and ephemeral:
 * a user is online in a workspace exactly while at least one of their
 * connections holds a live session key; nothing is simulated and nothing
 * about presence is ever written to PostgreSQL.
 *
 * <p>Redis layout (all keys are transient; losing Redis just resets
 * presence — clients reappear on their next heartbeat):
 * <ul>
 *   <li>{@code presence:session:{workspaceId}:{userId}:{sessionId}} — the
 *       per-connection key, value = last-seen instant, TTL =
 *       {@code syncspace.presence.ttl-seconds} (45 s by default). One key
 *       per connection, so closing one tab does not mark a user offline
 *       while another tab stays open. Heartbeats refresh the TTL.</li>
 *   <li>{@code presence:user-sessions:{workspaceId}:{userId}} — set of the
 *       user's session ids in the workspace, so liveness can be checked
 *       without a key scan. Entries for dead sessions are pruned lazily
 *       whenever liveness is checked.</li>
 *   <li>{@code presence:users:{workspaceId}} — set of user ids that have
 *       ever heartbeated in the workspace; {@code presence:workspaces} —
 *       set of workspace ids with presence activity. Both exist only so
 *       the sweeper can enumerate what to check.</li>
 *   <li>{@code presence:session-workspaces:{sessionId}} — set of workspace
 *       ids a session has heartbeated in, TTL'd like the session keys, so
 *       a disconnect can find every workspace to clean up.</li>
 *   <li>{@code presence:status:{workspaceId}} — hash, user id →
 *       {@code "online"} / {@code "offline"}: the last broadcast state,
 *       so transitions are broadcast exactly once.</li>
 *   <li>{@code presence:lastseen:{workspaceId}} — hash, user id → ISO
 *       instant of the last heartbeat, kept after a user goes offline so
 *       the REST list can still report when they were last seen.</li>
 * </ul>
 *
 * <p>Going offline is detected two ways, and the code documents which one
 * fired where:
 * <ul>
 *   <li><b>Disconnect listener (the fast path):</b>
 *       {@link PresenceDisconnectListener} handles Spring's
 *       {@code SessionDisconnectEvent} — fired when the client sends
 *       DISCONNECT <em>or</em> when the transport closes, including a
 *       missed STOMP heartbeat — deletes that session's keys, and
 *       broadcasts {@code PRESENCE_CHANGED offline} immediately when no
 *       other session of the user remains in the workspace.</li>
 *   <li><b>TTL-expiry sweep (the safety net):</b> {@link PresenceSweeper}
 *       runs every {@code syncspace.presence.sweep-interval-ms} (15 s by
 *       default) and, for every user whose last broadcast state is online
 *       but who holds no live session key, broadcasts offline. This covers
 *       the cases no disconnect event ever reaches the server — a crashed
 *       client, a killed network, a dead browser tab. Redis keyspace
 *       notifications are deliberately <em>not</em> used: they need a
 *       server-side {@code notify-keyspace-events} setting this service
 *       does not own, and a periodic sweep over the (small, membership
 *       bounded) user sets is sufficient at this scale. The REST list does
 *       not depend on either path: it derives online status live from the
 *       session keys, so a user whose key expired reads offline even
 *       before the next sweep broadcasts it.</li>
 * </ul>
 *
 * <p>Broadcasts go out only on a state transition (offline → online on a
 * heartbeat, online → offline on disconnect/sweep), never on every
 * heartbeat, so a room full of idling clients does not spam the topic.
 * A client that subscribes after a user came online recovers the current
 * state from {@code GET /workspaces/{id}/presence}.
 *
 * <p>Redis failures are treated as "presence unavailable", never as a
 * reason to fail a request: a heartbeat that cannot reach Redis is
 * logged and skipped, and the REST list reports everyone offline with
 * the last-seen values it can still read. Membership is always checked
 * first, against PostgreSQL, so a Redis outage never weakens
 * authorization.
 */
@Service
public class PresenceService {

    /** Heartbeat interval the frontend uses; the TTL is 3x that, so two missed beats are tolerated. */
    public static final long HEARTBEAT_INTERVAL_MILLIS = 15_000L;

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    private final StringRedisTemplate redis;
    private final WorkspaceMemberRepository members;
    private final RealtimeEventPublisher publisher;
    private final Duration ttl;

    public PresenceService(
            StringRedisTemplate redis,
            WorkspaceMemberRepository members,
            RealtimeEventPublisher publisher,
            @Value("${syncspace.presence.ttl-seconds:45}") long ttlSeconds) {
        this.redis = redis;
        this.members = members;
        this.publisher = publisher;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    // -- heartbeat ---------------------------------------------------------

    /**
     * Records a heartbeat from one connection of a workspace member.
     * Returns {@code true} when this heartbeat moved the user
     * offline → online (and a broadcast went out).
     *
     * @throws NotFoundException when the user is not a member of the workspace
     */
    @Transactional(readOnly = true)
    public boolean heartbeat(UUID userId, UUID workspaceId, String sessionId) {
        requireMembership(workspaceId, userId);
        Instant now = Instant.now();
        try {
            // Last-seen is written BEFORE the session key: a reader that
            // observes the user online must always find a last-seen
            // instant, never a null. (The session key is the liveness
            // signal; the hash is the timestamp behind it.)
            redis.opsForHash().put(lastSeenKey(workspaceId), userId.toString(), now.toString());
            redis.opsForValue().set(sessionKey(workspaceId, userId, sessionId), now.toString(), ttl);
            redis.opsForSet().add(userSessionsKey(workspaceId, userId), sessionId);
            redis.opsForSet().add(usersKey(workspaceId), userId.toString());
            redis.opsForSet().add(workspacesKey(), workspaceId.toString());
            redis.opsForSet().add(sessionWorkspacesKey(sessionId), workspaceId.toString());
            redis.expire(sessionWorkspacesKey(sessionId), ttl);
            String previous = readStatus(workspaceId, userId);
            if (!"online".equals(previous)) {
                writeStatus(workspaceId, userId, "online");
                broadcast(userId, workspaceId, "online", now);
                return true;
            }
            return false;
        } catch (DataAccessException ex) {
            // Redis is transport only: presence degrades to "unknown",
            // it never fails the heartbeat call.
            log.warn("Presence heartbeat not recorded (Redis unavailable): {}", ex.getMessage());
            return false;
        }
    }

    // -- REST list ---------------------------------------------------------

    /** Every member of the workspace with their live online status and last-seen instant. */
    @Transactional(readOnly = true)
    public List<PresenceMemberDto> listPresence(UUID userId, UUID workspaceId) {
        requireMembership(workspaceId, userId);
        return members.findByWorkspaceId(workspaceId).stream()
                .map(member -> {
                    UUID memberId = member.getUser().getId();
                    boolean online = hasLiveSession(workspaceId, memberId);
                    String lastSeen = readLastSeen(workspaceId, memberId);
                    if (lastSeen == null && online) {
                        // Belt and braces for the ordering above: the live
                        // session key itself carries the last-seen instant.
                        lastSeen = readLiveSessionLastSeen(workspaceId, memberId);
                    }
                    return new PresenceMemberDto(
                            memberId,
                            member.getUser().getEmail(),
                            member.getUser().getDisplayName(),
                            member.getRole(),
                            member.getJoinedAt(),
                            online ? "online" : "offline",
                            lastSeen == null ? null : Instant.parse(lastSeen));
                })
                .toList();
    }

    // -- disconnect --------------------------------------------------------

    /**
     * Cleans up one STOMP session: deletes its session keys in every
     * workspace it heartbeated in, and broadcasts offline for each
     * workspace where the user now holds no live session.
     */
    public void handleDisconnect(String sessionId, UUID userId) {
        Set<String> workspaceIds;
        try {
            workspaceIds = redis.opsForSet().members(sessionWorkspacesKey(sessionId));
        } catch (DataAccessException ex) {
            log.warn("Presence disconnect cleanup skipped (Redis unavailable): {}", ex.getMessage());
            return;
        }
        if (workspaceIds == null || workspaceIds.isEmpty()) {
            return;
        }
        for (String workspaceIdText : workspaceIds) {
            UUID workspaceId;
            try {
                workspaceId = UUID.fromString(workspaceIdText);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            try {
                redis.opsForSet().remove(userSessionsKey(workspaceId, userId), sessionId);
                redis.delete(sessionKey(workspaceId, userId, sessionId));
                if (!hasLiveSession(workspaceId, userId)) {
                    markOffline(userId, workspaceId);
                }
            } catch (DataAccessException ex) {
                log.warn("Presence disconnect cleanup failed for workspace {}: {}",
                        workspaceId, ex.getMessage());
            }
        }
        try {
            redis.delete(sessionWorkspacesKey(sessionId));
        } catch (DataAccessException ex) {
            log.warn("Presence session-workspaces cleanup failed: {}", ex.getMessage());
        }
    }

    // -- TTL-expiry sweep --------------------------------------------------

    /**
     * Safety net for connections that vanished without a disconnect event:
     * any user whose last broadcast state is online but who holds no live
     * session key is marked offline and the change is broadcast. Also
     * self-heals the reverse direction (live sessions but a stale offline
     * state, e.g. a broadcast lost while Redis was down).
     */
    public void sweepExpired() {
        Set<String> workspaceIds;
        try {
            workspaceIds = redis.opsForSet().members(workspacesKey());
        } catch (DataAccessException ex) {
            log.warn("Presence sweep skipped (Redis unavailable): {}", ex.getMessage());
            return;
        }
        if (workspaceIds == null) {
            return;
        }
        for (String workspaceIdText : workspaceIds) {
            UUID workspaceId;
            try {
                workspaceId = UUID.fromString(workspaceIdText);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            sweepWorkspace(workspaceId);
        }
    }

    private void sweepWorkspace(UUID workspaceId) {
        Set<String> userIds;
        try {
            userIds = redis.opsForSet().members(usersKey(workspaceId));
        } catch (DataAccessException ex) {
            log.warn("Presence sweep of workspace {} skipped (Redis unavailable): {}",
                    workspaceId, ex.getMessage());
            return;
        }
        if (userIds == null) {
            return;
        }
        for (String userIdText : userIds) {
            UUID userId;
            try {
                userId = UUID.fromString(userIdText);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            try {
                boolean live = hasLiveSession(workspaceId, userId);
                String status = readStatus(workspaceId, userId);
                if (!live && "online".equals(status)) {
                    markOffline(userId, workspaceId);
                } else if (live && !"online".equals(status)) {
                    writeStatus(workspaceId, userId, "online");
                    String lastSeen = readLastSeen(workspaceId, userId);
                    broadcast(userId, workspaceId, "online",
                            lastSeen == null ? Instant.now() : Instant.parse(lastSeen));
                }
            } catch (DataAccessException ex) {
                log.warn("Presence sweep of user {} in workspace {} failed: {}",
                        userId, workspaceId, ex.getMessage());
            }
        }
    }

    // -- helpers -----------------------------------------------------------

    /**
     * True when the user holds at least one live session key in the
     * workspace. Session ids in the set whose key has already expired
     * (crashed client, TTL lapsed between beats) are pruned here.
     */
    boolean hasLiveSession(UUID workspaceId, UUID userId) {
        Set<String> sessionIds;
        try {
            sessionIds = redis.opsForSet().members(userSessionsKey(workspaceId, userId));
        } catch (DataAccessException ex) {
            return false;
        }
        if (sessionIds == null || sessionIds.isEmpty()) {
            return false;
        }
        boolean live = false;
        for (String sessionId : sessionIds) {
            Boolean exists;
            try {
                exists = redis.hasKey(sessionKey(workspaceId, userId, sessionId));
            } catch (DataAccessException ex) {
                return false;
            }
            if (Boolean.TRUE.equals(exists)) {
                live = true;
            } else {
                try {
                    redis.opsForSet().remove(userSessionsKey(workspaceId, userId), sessionId);
                } catch (DataAccessException ex) {
                    // Pruning is best-effort; the stale id is harmless.
                    log.debug("Could not prune stale presence session {}: {}", sessionId, ex.getMessage());
                }
            }
        }
        return live;
    }

    /** Broadcasts offline and records it, keeping the stored last-seen instant. */
    private void markOffline(UUID userId, UUID workspaceId) {
        writeStatus(workspaceId, userId, "offline");
        String lastSeen = readLastSeen(workspaceId, userId);
        broadcast(userId, workspaceId, "offline",
                lastSeen == null ? Instant.now() : Instant.parse(lastSeen));
    }

    private void broadcast(UUID userId, UUID workspaceId, String status, Instant lastSeenAt) {
        publisher.publishToWorkspaceTopic(
                RealtimeEvent.create("PRESENCE_CHANGED", workspaceId, userId, userId, 1,
                        Map.of("userId", userId.toString(),
                                "status", status,
                                "lastSeenAt", lastSeenAt.toString())),
                "presence");
    }

    private String readStatus(UUID workspaceId, UUID userId) {
        try {
            Object value = redis.opsForHash().get(statusKey(workspaceId), userId.toString());
            return value == null ? null : value.toString();
        } catch (DataAccessException ex) {
            return null;
        }
    }

    private void writeStatus(UUID workspaceId, UUID userId, String status) {
        redis.opsForHash().put(statusKey(workspaceId), userId.toString(), status);
    }

    private String readLastSeen(UUID workspaceId, UUID userId) {
        try {
            Object value = redis.opsForHash().get(lastSeenKey(workspaceId), userId.toString());
            return value == null ? null : value.toString();
        } catch (DataAccessException ex) {
            return null;
        }
    }

    /** Last-seen instant from a live session key's value (it stores the heartbeat instant). */
    private String readLiveSessionLastSeen(UUID workspaceId, UUID userId) {
        Set<String> sessionIds;
        try {
            sessionIds = redis.opsForSet().members(userSessionsKey(workspaceId, userId));
        } catch (DataAccessException ex) {
            return null;
        }
        if (sessionIds == null) {
            return null;
        }
        for (String sessionId : sessionIds) {
            try {
                String value = redis.opsForValue().get(sessionKey(workspaceId, userId, sessionId));
                if (value != null) {
                    return value;
                }
            } catch (DataAccessException ex) {
                return null;
            }
        }
        return null;
    }

    private WorkspaceMember requireMembership(UUID workspaceId, UUID userId) {
        return members.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new NotFoundException("Workspace not found"));
    }

    // -- key layout (see the class javadoc) --------------------------------

    static String sessionKey(UUID workspaceId, UUID userId, String sessionId) {
        return "presence:session:" + workspaceId + ":" + userId + ":" + sessionId;
    }

    static String userSessionsKey(UUID workspaceId, UUID userId) {
        return "presence:user-sessions:" + workspaceId + ":" + userId;
    }

    static String usersKey(UUID workspaceId) {
        return "presence:users:" + workspaceId;
    }

    static String workspacesKey() {
        return "presence:workspaces";
    }

    static String sessionWorkspacesKey(String sessionId) {
        return "presence:session-workspaces:" + sessionId;
    }

    static String statusKey(UUID workspaceId) {
        return "presence:status:" + workspaceId;
    }

    static String lastSeenKey(UUID workspaceId) {
        return "presence:lastseen:" + workspaceId;
    }

    /** One workspace member with live presence state. */
    public record PresenceMemberDto(
            UUID userId,
            String email,
            String displayName,
            WorkspaceRole role,
            Instant joinedAt,
            String status,
            Instant lastSeenAt) {
    }
}
