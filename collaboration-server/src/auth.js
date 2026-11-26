/**
 * Connection authorization for the collaboration server.
 *
 * Two checks, both required, on every connection — the server never
 * trusts the client and never makes an authorization decision of its own:
 *  1. The JWT is verified locally (HMAC-SHA256, same secret the backend
 *     signs access tokens with). This proves the token is genuine and
 *     unexpired, and yields the user id for persistence bookkeeping.
 *  2. Document membership is verified with the backend:
 *     GET {BACKEND_URL}/api/v1/documents/{documentId} with the same
 *     bearer token. 200 means the user is a member of the owning
 *     workspace; anything else (401 bad token, 404 non-member or unknown
 *     document) means reject. The backend is the authority on membership,
 *     so a user removed from a workspace loses sync access immediately.
 *
 * Rejected connections are closed with WebSocket code 4401.
 */
import jwt from 'jsonwebtoken';

export const CLOSE_UNAUTHORIZED = 4401;

/** Verifies the JWT signature/expiry; returns the user id or null. */
export function verifyToken(token, jwtSecret) {
  if (!token) return null;
  try {
    // The backend signs with JJWT's Keys.hmacShaKeyFor, which picks the
    // HMAC algorithm from the secret length (32–47 bytes → HS256,
    // 48–63 → HS384, 64+ → HS512), so all three must be accepted: the
    // local default secret is 48 chars and signs HS384. The security
    // property is the shared secret, not the variant.
    const payload = jwt.verify(token, jwtSecret, {
      algorithms: ['HS256', 'HS384', 'HS512'],
    });
    return typeof payload.sub === 'string' ? payload.sub : null;
  } catch {
    return null;
  }
}

/**
 * Asks the backend whether the token holder may access the document.
 * Returns true only on HTTP 200.
 */
export async function verifyMembership(backendUrl, documentId, token) {
  let response;
  try {
    response = await fetch(
      `${backendUrl}/api/v1/documents/${encodeURIComponent(documentId)}`,
      { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(5000) },
    );
  } catch (err) {
    console.warn(`[auth] membership check failed for ${documentId}: ${err.message}`);
    return false;
  }
  return response.status === 200;
}

/**
 * Runs both checks. Returns `{ userId }` on success, `null` on any
 * failure (bad/expired token, non-member, backend unreachable).
 */
export async function authorizeConnection({ backendUrl, jwtSecret, documentId, token }) {
  const userId = verifyToken(token, jwtSecret);
  if (userId === null) return null;
  if (!(await verifyMembership(backendUrl, documentId, token))) return null;
  return { userId };
}
