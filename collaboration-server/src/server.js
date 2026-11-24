/**
 * SyncSpace collaboration server — Yjs document synchronization service.
 *
 * Responsibilities (see docs/architecture.md):
 *  - Authenticate every document sync connection and verify document
 *    membership with the Spring Boot backend before accepting it.
 *  - Synchronize Yjs CRDT updates between connected clients and broadcast
 *    awareness state (cursors, selections, presence in the document).
 *  - Persist Yjs binary document updates and snapshots to PostgreSQL so
 *    document state survives restarts.
 *
 * Foundation slice: the service boots, exposes a health endpoint, and is
 * wired into Docker Compose and Nginx. Yjs sync protocol, membership
 * verification, and CRDT persistence land with the collaborative-documents
 * slice; this file must not claim sync works before then.
 */

import http from 'node:http';

const PORT = Number(process.env.COLLAB_PORT ?? 3001);
const HOST = process.env.COLLAB_HOST ?? '0.0.0.0';

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  if (req.method === 'GET' && url.pathname === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', service: 'collaboration-server' }));
    return;
  }
  res.writeHead(404, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ error: 'not_found' }));
});

server.listen(PORT, HOST, () => {
  console.log(`collaboration-server listening on http://${HOST}:${PORT}`);
});
