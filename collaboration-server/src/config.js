/**
 * Configuration for the collaboration server. Everything comes from the
 * environment — no secrets are hardcoded. Defaults match the local
 * development setup (see the repo-root `.env.example`); Docker Compose
 * and any shared deployment override them.
 */
export function loadConfig(env = process.env) {
  return {
    port: Number(env.COLLAB_PORT ?? 3001),
    host: env.COLLAB_HOST ?? '0.0.0.0',
    // Spring Boot backend, used only for the membership check on connect.
    backendUrl: (env.BACKEND_URL ?? 'http://localhost:8080').replace(/\/$/, ''),
    // PostgreSQL, the durable store for Yjs updates and snapshots.
    databaseUrl:
      env.DATABASE_URL ??
      'postgres://syncspace:syncspace@[::1]:5432/syncspace?sslmode=disable',
    // HMAC secret the backend signs JWT access tokens with. Must match
    // the backend's JWT_SECRET; the local default matches the backend's
    // local default in backend/src/main/resources/application.yml.
    jwtSecret:
      env.JWT_SECRET ?? 'change-me-to-a-long-random-secret-at-least-32-chars',
    // Snapshot policy (see persistence.js): write a snapshot after this
    // many persisted updates, at most once per interval for dirty docs,
    // and whenever the last client leaves a document.
    snapshotEveryUpdates: Number(env.COLLAB_SNAPSHOT_EVERY_UPDATES ?? 50),
    snapshotIntervalMs: Number(env.COLLAB_SNAPSHOT_INTERVAL_MS ?? 60_000),
  };
}
