package com.syncspace.messaging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Channel and message endpoints. Membership, roles, and authorship are
 * enforced in {@link MessagingService}.
 *
 * <p>Message history is page/size paginated and chronological: page 0
 * holds the oldest messages. Default size 50, maximum 100.
 */
@RestController
@RequestMapping("/api/v1")
public class MessagingController {

    private final MessagingService messagingService;

    public MessagingController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    @GetMapping("/workspaces/{workspaceId}/channels")
    public List<MessagingService.ChannelDto> listChannels(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId) {
        return messagingService.listChannels(userId, workspaceId);
    }

    @PostMapping("/workspaces/{workspaceId}/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public MessagingService.ChannelDto createChannel(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateChannelRequest request) {
        return messagingService.createChannel(userId, workspaceId, request.name(), request.description());
    }

    @PostMapping("/channels/{channelId}/read")
    public MessagingService.ChannelReadDto markRead(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID channelId) {
        return messagingService.markRead(userId, channelId);
    }

    @GetMapping("/channels/{channelId}/messages")
    public MessagingService.PageDto<MessagingService.MessageDto> listMessages(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID channelId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return messagingService.listMessages(userId, channelId, page, size);
    }

    @PatchMapping("/messages/{messageId}")
    public MessagingService.MessageDto editMessage(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID messageId,
            @Valid @RequestBody UpdateMessageRequest request) {
        return messagingService.editMessage(userId, messageId, request.body());
    }

    @DeleteMapping("/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMessage(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID messageId) {
        messagingService.deleteMessage(userId, messageId);
    }

    public record CreateChannelRequest(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 2000) String description) {
    }

    public record UpdateMessageRequest(
            @NotBlank @Size(max = MessagingService.MAX_BODY_LENGTH) String body) {
    }
}
