package com.syncspace.documents;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Document metadata endpoints. Membership, roles, and workspace isolation
 * are enforced in {@link DocumentService}; content sync goes through the
 * Yjs collaboration server, not these endpoints.
 */
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/workspaces/{workspaceId}/documents")
    public DocumentService.DocumentListDto listDocuments(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId) {
        return documentService.listDocuments(userId, workspaceId);
    }

    @PostMapping("/workspaces/{workspaceId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.DocumentDto createDocument(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateDocumentRequest request) {
        return documentService.createDocument(userId, workspaceId, request.title());
    }

    @GetMapping("/documents/{documentId}")
    public DocumentService.DocumentDto getDocument(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID documentId) {
        return documentService.getDocument(userId, documentId);
    }

    @PatchMapping("/documents/{documentId}")
    public DocumentService.DocumentDto renameDocument(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID documentId,
            @Valid @RequestBody RenameDocumentRequest request) {
        return documentService.renameDocument(userId, documentId, request.title());
    }

    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID documentId) {
        documentService.deleteDocument(userId, documentId);
    }

    public record CreateDocumentRequest(
            @NotBlank @Size(max = DocumentService.MAX_TITLE_LENGTH) String title) {
    }

    public record RenameDocumentRequest(
            @NotBlank @Size(max = DocumentService.MAX_TITLE_LENGTH) String title) {
    }
}
