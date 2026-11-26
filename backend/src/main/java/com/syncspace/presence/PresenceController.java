package com.syncspace.presence;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Presence REST read. Membership is enforced in
 * {@link PresenceService} — non-members get 404, so workspace existence
 * is not leaked (same rule as the other workspace routes).
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class PresenceController {

    private final PresenceService presenceService;

    public PresenceController(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @GetMapping("/{workspaceId}/presence")
    public List<PresenceService.PresenceMemberDto> listPresence(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId) {
        return presenceService.listPresence(userId, workspaceId);
    }
}
