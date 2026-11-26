package com.syncspace.documents;

import com.syncspace.auth.User;
import com.syncspace.common.ForbiddenException;
import com.syncspace.common.NotFoundException;
import com.syncspace.realtime.RealtimeEvent;
import com.syncspace.realtime.RealtimeEventPublisher;
import com.syncspace.workspace.Workspace;
import com.syncspace.workspace.WorkspaceMember;
import com.syncspace.workspace.WorkspaceMemberRepository;
import com.syncspace.workspace.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documents;

    @Mock
    private WorkspaceMemberRepository members;

    @Mock
    private RealtimeEventPublisher publisher;

    private DocumentService service;
    private User owner;
    private User member;
    private User viewer;
    private User stranger;
    private Workspace workspace;
    private Workspace otherWorkspace;
    private Document document;

    @BeforeEach
    void setUp() {
        service = new DocumentService(documents, members, publisher);
        owner = user("owner@example.com");
        member = user("member@example.com");
        viewer = user("viewer@example.com");
        stranger = user("stranger@example.com");
        workspace = new Workspace("Team", "desc", owner);
        ReflectionTestUtils.setField(workspace, "id", UUID.randomUUID());
        otherWorkspace = new Workspace("Other", null, owner);
        ReflectionTestUtils.setField(otherWorkspace, "id", UUID.randomUUID());
        document = new Document(workspace, "Plan", owner);
        ReflectionTestUtils.setField(document, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(document, "createdAt", Instant.now());
        ReflectionTestUtils.setField(document, "updatedAt", Instant.now());
    }

    private User user(String email) {
        User user = new User(email, "Name", "hashed");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private void mockMembership(User user, Workspace workspace, WorkspaceRole role) {
        when(members.findByWorkspaceIdAndUserId(workspace.getId(), user.getId()))
                .thenReturn(Optional.of(new WorkspaceMember(workspace, user, role)));
    }

    @Test
    void createDocumentPersistsTrimmedTitle() {
        mockMembership(member, workspace, WorkspaceRole.MEMBER);
        when(documents.save(any(Document.class))).thenAnswer(inv -> {
            Document saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(saved, "createdAt", Instant.now());
            ReflectionTestUtils.setField(saved, "updatedAt", Instant.now());
            return saved;
        });

        DocumentService.DocumentDto dto =
                service.createDocument(member.getId(), workspace.getId(), "  Roadmap  ");

        assertThat(dto.title()).isEqualTo("Roadmap");
        assertThat(dto.workspaceId()).isEqualTo(workspace.getId());
        assertThat(dto.createdBy()).isEqualTo(member.getId());
        assertThat(dto.version()).isEqualTo(0);
        verify(publisher, never()).publishToWorkspaceTopic(any(), any());
    }

    @Test
    void createDocumentAsViewerIsForbidden() {
        mockMembership(viewer, workspace, WorkspaceRole.VIEWER);

        assertThatThrownBy(() -> service.createDocument(viewer.getId(), workspace.getId(), "x"))
                .isInstanceOf(ForbiddenException.class);
        verify(documents, never()).save(any());
    }

    @Test
    void createDocumentAsNonMemberIsNotFound() {
        when(members.findByWorkspaceIdAndUserId(workspace.getId(), stranger.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createDocument(stranger.getId(), workspace.getId(), "x"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createDocumentWithBlankTitleIsBadRequest() {
        mockMembership(member, workspace, WorkspaceRole.MEMBER);

        assertThatThrownBy(() -> service.createDocument(member.getId(), workspace.getId(), "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listDocumentsReturnsWorkspaceDocuments() {
        mockMembership(member, workspace, WorkspaceRole.MEMBER);
        when(documents.findByWorkspaceIdOrderByUpdatedAtDesc(workspace.getId()))
                .thenReturn(List.of(document));

        DocumentService.DocumentListDto result =
                service.listDocuments(member.getId(), workspace.getId());

        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).id()).isEqualTo(document.getId());
    }

    @Test
    void listDocumentsAsNonMemberIsNotFound() {
        when(members.findByWorkspaceIdAndUserId(workspace.getId(), stranger.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listDocuments(stranger.getId(), workspace.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getDocumentReturnsMetadataForMember() {
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        mockMembership(member, workspace, WorkspaceRole.MEMBER);

        DocumentService.DocumentDto dto = service.getDocument(member.getId(), document.getId());

        assertThat(dto.id()).isEqualTo(document.getId());
        assertThat(dto.workspaceId()).isEqualTo(workspace.getId());
    }

    @Test
    void getDocumentFromAnotherWorkspaceIsNotFound() {
        // The document belongs to `workspace`, but the caller proves
        // membership of `otherWorkspace` only: the owning workspace is
        // never consulted for them, so the document stays invisible.
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(members.findByWorkspaceIdAndUserId(workspace.getId(), member.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDocument(member.getId(), document.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getUnknownDocumentIsNotFound() {
        when(documents.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDocument(member.getId(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void renamePublishesDocumentUpdatedWithNewVersion() {
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        mockMembership(member, workspace, WorkspaceRole.MEMBER);
        when(documents.saveAndFlush(any(Document.class))).thenAnswer(inv -> {
            Document saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "version", 1L);
            return saved;
        });

        DocumentService.DocumentDto dto =
                service.renameDocument(member.getId(), document.getId(), "New title");

        assertThat(dto.title()).isEqualTo("New title");
        assertThat(dto.version()).isEqualTo(1);
        ArgumentCaptor<RealtimeEvent> event = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(publisher).publishToWorkspaceTopic(event.capture(), eq("documents"));
        assertThat(event.getValue().eventType()).isEqualTo("DOCUMENT_UPDATED");
        assertThat(event.getValue().workspaceId()).isEqualTo(workspace.getId());
        assertThat(event.getValue().resourceId()).isEqualTo(document.getId());
        assertThat(event.getValue().version()).isEqualTo(1);
        assertThat(event.getValue().payload().get("title")).isEqualTo("New title");
        assertThat(event.getValue().payload().get("documentId"))
                .isEqualTo(document.getId().toString());
    }

    @Test
    void renameAsViewerIsForbiddenAndPublishesNothing() {
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        mockMembership(viewer, workspace, WorkspaceRole.VIEWER);

        assertThatThrownBy(() -> service.renameDocument(viewer.getId(), document.getId(), "x"))
                .isInstanceOf(ForbiddenException.class);
        verify(publisher, never()).publishToWorkspaceTopic(any(), any());
    }

    @Test
    void deleteRemovesDocument() {
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        mockMembership(member, workspace, WorkspaceRole.MEMBER);

        service.deleteDocument(member.getId(), document.getId());

        verify(documents).delete(document);
    }

    @Test
    void deleteAsViewerIsForbidden() {
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        mockMembership(viewer, workspace, WorkspaceRole.VIEWER);

        assertThatThrownBy(() -> service.deleteDocument(viewer.getId(), document.getId()))
                .isInstanceOf(ForbiddenException.class);
        verify(documents, never()).delete(any());
    }

    @Test
    void deleteUnknownDocumentIsNotFound() {
        when(documents.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDocument(member.getId(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }
}
