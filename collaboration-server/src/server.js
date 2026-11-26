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
 * Wiring: plain HTTP server for /health plus a WebSocket server on the
 * same port. Clients connect to ws://host/collab/{documentId}?token=…
 * (through Nginx at /collab/; direct at :3001). The JWT may also be
 * passed as an `Authorization: Bearer …` header. Unauthorized
 * connections are closed with code 4401.
 *
 * Run directly (`npm start`) or import {@link createCollabServer} in
 * tests, which need to start/stop the server on an ephemeral port.
 */
import http from 'node:http';
import { WebSocketServer } from 'ws';
import { loadConfig } from './config.js';
import { PostgresPersistence } from './persistence.js';
import { authorizeConnection, CLOSE_UNAUTHORIZED } from './auth.js';
import { RoomManager } from './collab.js';

export function createCollabServer(config = loadConfig()) {
  const persistence = new PostgresPersistence(config.databaseUrl);
  const rooms = new RoomManager(persistence, {
    snapshotEveryUpdates: config.snapshotEveryUpdates,
    snapshotIntervalMs: config.snapshotIntervalMs,
  });

  const server = http.createServer((req, res) => {
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
    if (req.method === 'GET' && url.pathname === '/health') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({
        status: 'ok',
        service: 'collaboration-server',
        persistence: persistence.disabled ? 'disabled' : 'ok',
        rooms: rooms.roomCount,
        connections: rooms.connectionCount,
      }));
      return;
    }
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: 'not_found' }));
  });

  const wss = new WebSocketServer({ noServer: true });

  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
    const match = /^\/collab\/([^/]+)$/.exec(url.pathname);
    if (!match) {
      socket.destroy();
      return;
    }
    const documentId = decodeURIComponent(match[1]);
    // Accept the upgrade first, then authorize: a rejected connection
    // must close with code 4401, which is only observable on an open
    // WebSocket. No sync message is processed before authorization
    // succeeds (message handlers are attached only in addConnection,
    // after both auth checks pass).
    wss.handleUpgrade(req, socket, head, (ws) => {
      // Messages may arrive before authorization finishes (the client
      // sends SyncStep1 on open). The ws library drops frames with no
      // 'message' listener, so queue them until auth resolves; they are
      // replayed in order once the connection is accepted, and dropped
      // with the connection when it is rejected.
      const pending = [];
      const queueMessage = (data) => pending.push(data);
      ws.on('message', queueMessage);
      void (async () => {
        const token =
          url.searchParams.get('token') ??
          req.headers.authorization?.replace(/^Bearer\s+/i, '');
        const auth = await authorizeConnection({
          backendUrl: config.backendUrl,
          jwtSecret: config.jwtSecret,
          documentId,
          token,
        });
        ws.off('message', queueMessage);
        if (auth === null) {
          ws.close(CLOSE_UNAUTHORIZED, 'unauthorized');
          return;
        }
        const room = await rooms.getOrCreateRoom(documentId);
        await rooms.addConnection(room, ws, auth.userId, pending);
      })();
    });
  });

  let listening;
  const ready = new Promise((resolve) => {
    listening = server.listen(config.port, config.host, resolve);
  });

  return {
    server,
    config,
    persistence,
    rooms,
    ready,
    address() {
      return server.address();
    },
    async close() {
      await rooms.close();
      await persistence.close();
      // Drop keep-alive sockets (undici fetch connections from the
      // membership checks and /health probes) so close() actually
      // resolves instead of waiting on idle connections.
      server.closeAllConnections?.();
      await new Promise((resolve) => {
        if (listening) listening.close(resolve);
        else resolve();
      });
      wss.close();
    },
  };
}

// Entry point: `node src/server.js`.
if (import.meta.url === `file://${process.argv[1]}`) {
  const collab = createCollabServer();
  await collab.ready;
  console.log(
    `collaboration-server listening on http://${collab.config.host}:${collab.config.port}`,
  );
  const shutdown = async () => {
    await collab.close();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}
