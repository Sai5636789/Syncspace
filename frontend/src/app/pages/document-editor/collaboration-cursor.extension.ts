import { Extension } from '@tiptap/core';
import { WebsocketProvider } from 'y-websocket';
import { defaultSelectionBuilder, yCursorPlugin } from '@tiptap/y-tiptap';

export interface CollabCursorUser {
  name: string;
  color: string;
  /** Present for the local user; remote awareness states may omit it. */
  userId?: string | null;
}

export interface CollaborationCursorOptions {
  provider: WebsocketProvider;
  user: CollabCursorUser;
}

export interface CollaborationCursorStorage {
  users: (CollabCursorUser & { clientId: number })[];
}

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    collaborationCursor: {
      updateUser: (attributes: CollabCursorUser) => ReturnType;
    };
  }
}

function awarenessStatesToArray(
  states: Map<number, { user?: CollabCursorUser }>,
): (CollabCursorUser & { clientId: number })[] {
  return Array.from(states.entries())
    .filter(([, state]) => state.user?.name !== undefined)
    .map(([clientId, state]) => ({ clientId, ...(state.user as CollabCursorUser) }));
}

/** Renders a remote caret in the user's color with a name label. */
function renderCursor(user: CollabCursorUser, _clientId: number): HTMLElement {
  const cursor = document.createElement('span');
  cursor.classList.add('collaboration-cursor__caret');
  cursor.setAttribute('style', `border-color: ${user.color}`);
  const label = document.createElement('div');
  label.classList.add('collaboration-cursor__label');
  label.setAttribute('style', `background-color: ${user.color}`);
  label.insertBefore(document.createTextNode(user.name), null);
  cursor.insertBefore(label, null);
  return cursor;
}

/**
 * Remote cursors and selections for the collaborative editor.
 *
 * There is no working `@tiptap/extension-collaboration-cursor` release
 * for TipTap 3.35: its latest (3.0.0, a mislabeled 2.5.0) builds on
 * `y-prosemirror`'s cursor plugin, whose sync-plugin key is a different
 * instance from the one `@tiptap/y-tiptap`'s Collaboration extension
 * uses — the editor crashes at creation reading `.doc` of an undefined
 * sync state. This local extension wires `@tiptap/y-tiptap`'s own
 * `yCursorPlugin` (same key family as the Collaboration extension)
 * directly, with the same caret rendering and awareness-user contract
 * the published extension provides. The local user is published to
 * awareness here; the participant list reads the same awareness state
 * via CollabService.
 */
export const CollaborationCursor = Extension.create<
  CollaborationCursorOptions,
  CollaborationCursorStorage
>({
  name: 'collaborationCursor',

  addOptions() {
    return {
      provider: null as unknown as WebsocketProvider,
      user: { name: '', color: '#888888', userId: null },
    };
  },

  addStorage() {
    return { users: [] };
  },

  addCommands() {
    return {
      updateUser: (attributes: CollabCursorUser) => (): boolean => {
        this.options.user = attributes;
        this.options.provider.awareness.setLocalStateField('user', this.options.user);
        return true;
      },
    };
  },

  addProseMirrorPlugins() {
    return [
      yCursorPlugin(
        (() => {
          this.options.provider.awareness.setLocalStateField('user', this.options.user);
          this.storage.users = awarenessStatesToArray(
            this.options.provider.awareness.getStates() as Map<number, { user?: CollabCursorUser }>,
          );
          this.options.provider.awareness.on('update', () => {
            this.storage.users = awarenessStatesToArray(
              this.options.provider.awareness.getStates() as Map<number, { user?: CollabCursorUser }>,
            );
          });
          return this.options.provider.awareness;
        })(),
        {
          cursorBuilder: renderCursor,
          selectionBuilder: defaultSelectionBuilder,
        },
      ),
    ];
  },
});
