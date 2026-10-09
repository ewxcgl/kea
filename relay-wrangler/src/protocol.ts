/**
 * Control protocol — the cleartext half of PROTOCOL.md §6.
 *
 * This is deliberately the *only* thing the edge can read. Everything carrying
 * geography travels as an opaque binary frame that this relay never parses;
 * see room.ts.
 *
 * The shapes are byte-compatible with relay/relay.py. If the two ever drift,
 * test_equivalence.py is the thing that catches it.
 */

/** PROTOCOL.md §10. Keep these in step with the Python reference. */
export const HEARTBEAT_SEC = 25;
export const MAX_FRAME_BYTES = 16_384;
export const MEMBER_TTL_MS = 90_000;
export const SWEEP_INTERVAL_MS = 15_000;
export const MAX_MEMBERS_PER_GROUP = 512;
export const PULL_MAX_SEC = 86_400;

export const PROTOCOL_VERSION = 1;

export interface RosterEntry {
  dev: string;
  name: string;
  pk: string;
  on: boolean;
  watching: boolean;
  seen: number;
}

export interface Hello {
  t: "hello";
  v: number;
  group: string;
  dev: string;
  name: string;
  pk: string;
}

export interface Watch {
  t: "watch";
  dev: string;
  on: boolean;
}

export interface Pull {
  t: "pull";
  dev: string;
  ttl: number;
}

export interface Leave {
  t: "leave";
  group: string;
  dev: string;
}

export interface Ping {
  t: "ping";
  ts: number;
}

/** Anything we did not recognise is ignored rather than treated as an error. */
export function isHello(v: unknown): v is Hello {
  return typeof v === "object" && v !== null && (v as Hello).t === "hello";
}

export function controlType(v: unknown): string | null {
  if (typeof v !== "object" || v === null) return null;
  const t = (v as { t?: unknown }).t;
  return typeof t === "string" ? t : null;
}

export function str(v: unknown, max = 256): string {
  return typeof v === "string" ? v.slice(0, max) : "";
}

/** base64url -> Uint8Array, used to derive the Durable Object id. */
export function b64urlToBytes(s: string): Uint8Array | null {
  try {
    const padded = s.replace(/-/g, "+").replace(/_/g, "/");
    const bin = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

/** Crockford-free hex; the DO id must be [0-9a-f]{32}. */
export function toHex(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += b.toString(16).padStart(2, "0");
  return s.slice(0, 32);
}