package com.syncspace.documents;

import com.syncspace.common.ForbiddenException;
import com.syncspace.common.NotFoundException;
import com.syncspace.realtime.RealtimeEvent;
import com.syncspace.realtime.RealtimeEventPublisher;
import com.syncspace.workspace.WorkspaceMember;
import com.syncspace.workspace.WorkspaceMemberRepository;
import com.syncspace.workspace.WorkspaceRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Document metadata (titles, ownership). Content sync is Yjs and lives in
 * the collaboration server; this service owns only the metadata rows and
 * the authorization around them.
 *
 * <p>Every operation first proves the caller is a member of the owning
 * workspace; non-members get 404 (existence is not leaked) and members
 * without the required role get 403. A rename publishes a
 * {@code DOCUMENT_UPDATED} envelope to
 * {@code /topic/workspaces/{id}/documents}, but only after the database
 * transaction commits — clients never hear about a rename that rolled
 * back. Deletes cascade in the database to {@code document_updates} and
 * {@code document_snapshots}, so no orphaned Yjs state survives.
 */
@Service
public class DocumentService {

    /** Maximum document title length, enforced by Bean Validation as well. */
    public static final int MAX_TITLE_LENGTH = 200;

    private final DocumentRepository documents;
    private final WorkspaceMemberRepository members;
    private final RealtimeEventPublisher publisher;

    public DocumentService(
            DocumentRepository documents,
            WorkspaceMemberRepository members,
            RealtimeEventPublisher publisher) {
        this.documents = documents;
        this.members = members;
        this.publisher = publisher;
    }

    @Transactional(readOnly = true)
    public DocumentListDto listDocuments(UUID userId, UUID workspaceId) {
        requireMembership(workspaceId, userId);
        List<DocumentDto> docs = documents.findByWorkspaceIdOrderByUpdatedAtDesc(workspaceId)
                .stream()
                .map(this::toDto)
                .toList();
        return new DocumentListDto(docs);
    }

    @Transactional
    public DocumentDto createDocument(UUID userId, UUID workspaceId, String title) {
        WorkspaceMember membership = requireRole(workspaceId, userId, WorkspaceRole.MEMBER);
        String trimmed = requireTitle(title);
        Document document = documents.save(new Document(
                membership.getWorkspace(), trimmed, membership.getUser()));
        return toDto(document);
    }

    @Transactional(readOnly = true)
    public DocumentDto getDocument(UUID userId, UUID documentId) {
        Document document = requireDocument(documentId);
        requireMembership(document.getWorkspace().getId(), userId);
        return toDto(document);
    }

    @Transactional
    public DocumentDto renameDocument(UUID userId, UUID documentId, String title) {
        Document document = requireDocument(documentId);
        requireRole(document.getWorkspace().getId(), userId, WorkspaceRole.MEMBER);
        document.setTitle(requireTitle(title));
        // saveAndFlush (not save): the @Version increment happens at
        // flush, and the DTO and DOCUMENT_UPDATED event below must carry
        // the new version, not the pre-flush one.
        Document saved = documents.saveAndFlush(document);
        DocumentDto dto = toDto(saved);
        publishAfterCommit(RealtimeEvent.create("DOCUMENT_UPDATED",
                saved.getWorkspace().getId(), saved.getId(), userId, saved.getVersion(),
                Map.of("documentId", saved.getId().toString(),
                        "title", saved.getTitle(),
                        "updatedAt", saved.getUpdatedAt().toString(),
                        "version", saved.getVersion())),
                "documents");
        return dto;
    }

    @Transactional
    public void deleteDocument(UUID userId, UUID documentId) {
        Document document = requireDocument(documentId);
        requireRole(document.getWorkspace().getId(), userId, WorkspaceRole.MEMBER);
        documents.delete(document);
    }

    // -- helpers -----------------------------------------------------------

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        String trimmed = title.trim();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title must be at most 200 characters");
        }
        return trimmed;
    }

    private WorkspaceMember requireMembership(UUID workspaceId, UUID userId) {
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

    private Document requireDocument(UUID documentId) {
        return documents.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
    }

    /**
     * Broadcasts only after the surrounding transaction commits, so clients
     * never receive an event for a row that rolled back. With no active
     * transaction (e.g. a direct service call in a unit test) the event is
     * published immediately — there is nothing to wait for.
     */
    private void publishAfterCommit(RealtimeEvent event, String topicSuffix) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publishToWorkspaceTopic(event, topicSuffix);
                }
            });
        } else {
            publisher.publishToWorkspaceTopic(event, topicSuffix);
        }
    }

    private DocumentDto toDto(Document document) {
        return new DocumentDto(
                document.getId(), document.getWorkspace().getId(), document.getTitle(),
                document.getCreatedBy() == null ? null : document.getCreatedBy().getId(),
                document.getVersion(), document.getCreatedAt(), document.getUpdatedAt());
    }

    public record DocumentDto(UUID id, UUID workspaceId, String title, UUID createdBy,
                              long version, Instant createdAt, Instant updatedAt) {
    }

    public record DocumentListDto(List<DocumentDto> documents) {
    }
}
