package com.syncspace.workspace;

/** Membership roles, ordered by privilege. OWNER outranks ADMIN outranks MEMBER outranks VIEWER. */
public enum WorkspaceRole {
    OWNER,
    ADMIN,
    MEMBER,
    VIEWER;

    public boolean atLeast(WorkspaceRole minimum) {
        return this.ordinal() <= minimum.ordinal();
    }
}
