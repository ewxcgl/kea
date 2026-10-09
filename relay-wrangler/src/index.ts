/**
 * KEA relay — Cloudflare Worker.
 *
 * Routing only. All group state lives in a Durable Object, one per group, so
 * this Worker stays trivially small and horizontally scalable.
 *
 *   wss://<worker>/ws/<base64url groupId>
 *   GET  https://<worker>/health
 *
 * Content boundary: the Worker never parses a payload. It picks an object by
 * group id and hands the bytes over.
 */

import { Room, type Env } from "./room";
import { b64urlToBytes, MAX_FRAME_BYTES, toHex } from "./protocol";

export { Room };
export type { Env };

/**
 * The group id travels in the path rather than the query string.
 *
 * Slightly more verbose, but it keeps a long base64 key out of access logs and
 * out of anything that treats a query string specially.
 */
const WS_PATH = /^\/ws\/([A-Za-z0-9_-]{8,128})$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    // Unauthenticated liveness probe. Reveals counts, nothing else.
    if (url.pathname === "/health") {
      return json({ ok: true, service: "kea-relay", v: 1 });
    }

    if (url.pathname === "/") {
      return json({
        service: "kea-relay",
        ws: "/ws/<base64url groupId>",
        note: "end-to-end encrypted; this edge cannot read any position",
      });
    }

    const match = WS_PATH.exec(url.pathname);
    if (!match) {
      return new Response("not found", { status: 404 });
    }
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected a WebSocket upgrade", { status: 400 });
    }

    const bytes = b64urlToBytes(match[1]);
    if (bytes === null || bytes.length !== 16) {
      // A group id is exactly 16 bytes; anything else is not one of ours.
      return new Response("bad group id", { status: 400 });
    }

    // nameFromHex keeps the DO id deterministic, so every client in a group
    // reaches the same object.
    const id = env.ROOM.idFromName(toHex(bytes));
    const stub = env.ROOM.get(id);

    // Forward verbatim. The Worker never looks inside a frame.
    const mutable = new Request(request);
    (mutable as unknown as { cf: { websocket: unknown } }).cf = {
      websocket: new WebSocketPair(),
    } as never;

    return stub.fetch(mutable);
  },
};

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    headers: {
      "content-type": "application/json",
      // Relay responses are never cacheable.
      "cache-control": "no-store",
    },
  });
}

export const MAX_FRAME = MAX_FRAME_BYTES;