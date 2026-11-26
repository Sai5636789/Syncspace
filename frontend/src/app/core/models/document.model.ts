/**
 * Document metadata types. Shapes follow docs/api-contracts.md
 * (Documents section). Content lives in Yjs state on the collaboration
 * server — these endpoints carry metadata only.
 */

export interface SyncDocument {
  id: string;
  workspaceId: string;
  title: string;
  createdBy: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentList {
  documents: SyncDocument[];
}
