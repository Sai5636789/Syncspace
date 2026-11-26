/**
 * Collaboration server tests (node --test).
 *
 * Covers: /health, unauthorized connections (bad token, missing token,
 * valid token but non-member → close 4401), two-client Yjs sync
 * convergence with awareness, update persistence to PostgreSQL, and
 * state recovery after a server restart (snapshot + update log reload).
 *
 * The backend is stubbed: it answers GET /api/v1/documents/{id} with
 * 200 only for the seeded test document, 404 otherwise — the same
 * contract the real backend upholds (200 = member, anything else =
 * reject). The database is the real local PostgreSQL; the seeded
 * workspace/document rows are removed afterwards.
 */
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import pg from 'pg';
import jwt from 'jsonwebtoken';
import { WebSocket } from 'ws';
import * as Y from 'yjs';
import { WebsocketProvider } from 'y-websocket';
import { createCollabServer } from '../src/server.js';

const JWT_SECRET = 'test-secret-that-is-long-enough-for-hs256';
const DATABASE_URL =
  process.env.DATABASE_URL ??
  'postgres://syncspace:syncspace@[::1]:5432/syncspace?sslmode=disable';

const pool = new pg.Pool({ connectionString: DATABASE_URL });

let stub;
let stubUrl;
let collab;
let port;
let userId;
let workspaceId;
let documentId;

function signToken(sub, secret = JWT_SECRET) {
  return jwt.sign({ sub }, secret, { algorithm: 'HS256', expiresIn: '15m' });
}

async function waitFor(fn, { timeout = 10_000, interval = 100, label = 'condition' } = {}) {
  const deadline = Date.now() + timeout;
  for (;;) {
    try {
      const value = await fn();
      if (value) return value;
    } catch {
      // keep waiting
    }
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${label}`);
    await new Promise((resolve) => setTimeout(resolve, interval));
  }
}

/** Connects a raw WebSocket and resolves with its close code (or rejects on open). */
function connectExpectClose(url, { timeout = 5000 } = {}) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const timer = setTimeout(() => {
      ws.terminate();
      reject(new Error('connection did not close in time'));
    }, timeout);
    ws.on('close', (code) => {
      clearTimeout(timer);
      resolve(code);
    });
    ws.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

before(async () => {
  // Stub backend: only the seeded document is "visible" (member).
  stub = http.createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost');
    const match = /^\/api\/v1\/documents\/([^/]+)$/.exec(url.pathname);
    const authorized = req.headers.authorization?.startsWith('Bearer ');
    if (match && req.method === 'GET' && authorized && match[1] === documentId) {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ id: documentId }));
      return;
    }
    res.writeHead(match ? 404 : 404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: { code: 'NOT_FOUND', message: 'not found' } }));
  });
  await new Promise((resolve) => stub.listen(0, '127.0.0.1', resolve));
  stubUrl = `http://127.0.0.1:${stub.address().port}`;

  // Seed a real user + workspace + document so persistence FKs hold.
  const user = await pool.query(
    `INSERT INTO users (email, display_name, password_hash)
     VALUES ($1, 'Collab Tester', 'hashed') RETURNING id`,
    [`collab-test-${Date.now()}@example.com`],
  );
  userId = user.rows[0].id;
  const workspace = await pool.query(
    `INSERT INTO workspaces (name, owner_id) VALUES ('Collab test WS', $1) RETURNING id`,
    [userId],
  );
  workspaceId = workspace.rows[0].id;
  const document = await pool.query(
    `INSERT INTO documents (workspace_id, title, created_by_id)
     VALUES ($1, 'Collab test doc', $2) RETURNING id`,
    [workspaceId, userId],
  );
  documentId = document.rows[0].id;

  collab = createCollabServer({
    port: 0,
    host: '127.0.0.1',
    backendUrl: stubUrl,
    databaseUrl: DATABASE_URL,
    jwtSecret: JWT_SECRET,
    snapshotEveryUpdates: 2,
    snapshotIntervalMs: 60_000,
  });
  await collab.ready;
  port = collab.address().port;
  assert.equal(await collab.persistence.ping(), true, 'persistence must reach Postgres');
});

after(async () => {
  await collab?.close().catch(() => {});
  // Drop keep-alive sockets from the membership-check fetches so the
  // stub server's close() resolves instead of waiting on idle clients.
  stub?.closeAllConnections?.();
  await new Promise((resolve) => stub?.close(resolve));
  // Deleting the workspace cascades to the document, its updates, and
  // its snapshots.
  await pool.query('DELETE FROM workspaces WHERE id = $1', [workspaceId]).catch(() => {});
  await pool.query('DELETE FROM users WHERE id = $1', [userId]).catch(() => {});
  await pool.end();
});

test('health endpoint reports ok with persistence up', async () => {
  const res = await fetch(`http://127.0.0.1:${port}/health`);
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.status, 'ok');
  assert.equal(body.service, 'collaboration-server');
  assert.equal(body.persistence, 'ok');
});

test('bad token closes with 4401', async () => {
  const code = await connectExpectClose(
    `ws://127.0.0.1:${port}/collab/${documentId}?token=not-a-jwt`,
  );
  assert.equal(code, 4401);
});

test('missing token closes with 4401', async () => {
  const code = await connectExpectClose(`ws://127.0.0.1:${port}/collab/${documentId}`);
  assert.equal(code, 4401);
});

test('valid token but non-member document closes with 4401', async () => {
  const otherDocumentId = '00000000-0000-0000-0000-000000000000';
  const code = await connectExpectClose(
    `ws://127.0.0.1:${port}/collab/${otherDocumentId}?token=${signToken(userId)}`,
  );
  assert.equal(code, 4401);
});

test('token signed with HS384 (backend default key length) is accepted', async () => {
  // The backend signs with JJWT, which picks HS384 for a 48-byte
  // secret; a verifier that only allowed HS256 would 4401 every real
  // token. The connection must stay open past authorization.
  const token = jwt.sign({ sub: userId }, JWT_SECRET, {
    algorithm: 'HS384',
    expiresIn: '15m',
  });
  const ws = new WebSocket(`ws://127.0.0.1:${port}/collab/${documentId}?token=${token}`);
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(resolve, 1500);
      ws.on('close', (code) => {
        clearTimeout(timer);
        reject(new Error(`connection closed with ${code}`));
      });
      ws.on('error', (err) => {
        clearTimeout(timer);
        reject(err);
      });
    });
    assert.equal(ws.readyState, WebSocket.OPEN);
  } finally {
    ws.close();
  }
});

test('two clients converge, share awareness, persist, and reload after restart', async () => {
  const token = signToken(userId);
  const docA = new Y.Doc();
  const docB = new Y.Doc();
  const providerA = new WebsocketProvider(`ws://127.0.0.1:${port}/collab`, documentId, docA, {
    params: { token },
  });
  const providerB = new WebsocketProvider(`ws://127.0.0.1:${port}/collab`, documentId, docB, {
    params: { token },
  });
  try {
    await waitFor(() => providerA.synced && providerB.synced, { label: 'initial sync' });

    // Concurrent edits from both sides.
    docA.getText('content').insert(0, 'Hello from A');
    docB.getText('content').insert(0, 'Hello from B. ');
    await waitFor(
      () => {
        const a = docA.getText('content').toString();
        const b = docB.getText('content').toString();
        return a === b && a.includes('Hello from A') && a.includes('Hello from B. ');
      },
      { label: 'two-client convergence' },
    );
    const converged = docA.getText('content').toString();

    // Awareness: A's cursor/user state reaches B.
    providerA.awareness.setLocalStateField('user', { name: 'Ana', color: '#ff0000' });
    await waitFor(
      () => {
        for (const state of providerB.awareness.getStates().values()) {
          if (state?.user?.name === 'Ana') return true;
        }
        return false;
      },
      { label: 'awareness propagation' },
    );

    // Persistence: updates landed in document_updates.
    await waitFor(
      async () => {
        const result = await pool.query(
          'SELECT COUNT(*)::int AS n FROM document_updates WHERE document_id = $1',
          [documentId],
        );
        return result.rows[0].n >= 2 ? result.rows[0].n : false;
      },
      { label: 'updates persisted' },
    );

    // Restart the server (same port): the room drops, the last
    // disconnect snapshots, and the new instance reloads from Postgres.
    providerA.destroy();
    providerB.destroy();
    await collab.close();
    collab = createCollabServer({
      port,
      host: '127.0.0.1',
      backendUrl: stubUrl,
      databaseUrl: DATABASE_URL,
      jwtSecret: JWT_SECRET,
      snapshotEveryUpdates: 2,
      snapshotIntervalMs: 60_000,
    });
    await collab.ready;

    const snapshots = await pool.query(
      'SELECT COUNT(*)::int AS n FROM document_snapshots WHERE document_id = $1',
      [documentId],
    );
    assert.ok(snapshots.rows[0].n >= 1, 'at least one snapshot row exists after restart');

    const docC = new Y.Doc();
    const providerC = new WebsocketProvider(`ws://127.0.0.1:${port}/collab`, documentId, docC, {
      params: { token },
    });
    try {
      await waitFor(() => providerC.synced, { label: 'reconnect sync' });
      await waitFor(
        () => docC.getText('content').toString() === converged,
        { label: 'reloaded state matches converged content' },
      );
      assert.equal(docC.getText('content').toString(), converged);
    } finally {
      providerC.destroy();
      // Destroying the doc destroys its Awareness, whose 3 s
      // housekeeping interval would otherwise keep the test process
      // alive after the suite finishes.
      docC.destroy();
    }
  } finally {
    providerA.destroy();
    providerB.destroy();
    docA.destroy();
    docB.destroy();
  }
});
