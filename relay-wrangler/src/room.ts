/**
 * One Durable Object per group.
 *
 * This is the port of relay/relay.py's in-memory `Group`, and the structure is
 * a strict improvement: the Python version kept every group in a single
 * process's heap, which forced `--max-instances 1` on every host. Here each
 * group is isolated in its own object, so the Worker can scale horizontally and
 * one busy group cannot stall another.
 *
 * WebSockets use the **Hibernation API** (`acceptWebSocket` +
 * `webSocketMessage`/`webSocketClose`/`webSocketError`). The object can be
 * evicted from memory while sockets stay open, which means an idle family group
 * costs essentially nothing and does not cold-start. Per-socket state lives in
 * the socket's attachment, and the roster lives in storage, so both survive
 * eviction.
 *
 * CONTENT BOUNDARY: binary frames are forwarded verbatim and never inspected.
 * This file cannot read a coordinate even if it wanted to.
 */

import {
  b64urlToBytes,
  controlType,
  HEARTBEAT_SEC,
  MAX_FRAME_BYTES,
  MAX_MEMBERS_PER_GROUP,
  MEMBER_TTL_MS,
  PULL_MAX_SEC,
  PROTOCOL_VERSION,
  str,
  SWEEP_INTERVAL_MS,
  toHex,
  type RosterEntry,
} from "./protocol";

export interface Env {
  ROOM: DurableObjectNamespace;
}

/** Per-socket metadata, survives hibernation via serializeAttachment. */
interface Attachment {
  dev: string;
  name: string;
  pk: string;
}

/** Roster entry as persisted in storage. */
interface MemberRecord {
  dev: string;
  name: string;
  pk: string;
  watching: boolean;
  seen: number;
}

const MEMBERS_KEY = "members";

export class Room {
  constructor(private readonly state: DurableObjectState) {}

  // ---- lifecycle --------------------------------------------------------

  async fetch(request: Request): Promise<Response> {
    try {
      if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("expected a WebSocket upgrade", { status: 400 });
      }

      const pair = new WebSocketPair();
      const client = pair[0];
      const server = pair[1];

      this.state.acceptWebSocket(server);
      // Tag the socket immediately so the message handler can identify the
      // sender before any `hello` arrives.
      server.serializeAttachment({ dev: "", name: "", pk: "" } satisfies Attachment);

      this.state.storage.setAlarm(Date.now() + SWEEP_INTERVAL_MS);

      return new Response(null, { status: 101, webSocket: client });
    } catch (err) {
      // TEMPORARY diagnostic: surface the exception instead of an opaque 1101.
      const msg = err instanceof Error ? `${err.name}: ${err.message}` : String(err);
      return new Response(`room-fetch: ${msg}`, { status: 500 });
    }
  }

  // ---- hibernation handlers --------------------------------------------

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const att = this.attachment(ws);

    if (typeof message !== "string") {
      // Opaque sealed payload. Fan it out untouched, minus the sender, and
      // never look at a single byte of it.
      if (message.byteLength > MAX_FRAME_BYTES) return;
      const members = await this.loadMembers();
      if (!att.dev || !members.has(att.dev)) return;
      for (const other of this.state.getWebSockets()) {
        if (other === ws) continue;
        const a = this.attachment(other);
        if (!a.dev || !members.has(a.dev)) continue;
        this.send(other, message);
      }
      return;
    }

    let parsed: unknown;
    try {
      parsed = JSON.parse(message);
    } catch {
      return; // Malformed control frame: ignore, never throw into the socket.
    }

    const type = controlType(parsed);
    switch (type) {
      case "hello":
        await this.onHello(ws, parsed as Record<string, unknown>);
        return;
      case "watch":
        await this.onWatch(ws, parsed as Record<string, unknown>);
        return;
      case "pull":
        await this.onPull(ws, parsed as Record<string, unknown>);
        return;
      case "leave":
        ws.close(1000, "bye");
        return;
      case "ping":
        this.sendJson(ws, { t: "pong", ts: (parsed as { ts?: number }).ts ?? 0 });
        return;
      default:
        return; // Unknown type: a newer client. Ignore it gracefully.
    }
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    await this.onGone(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    await this.onGone(ws);
  }

  async alarm(): Promise<void> {
    this.state.storage.setAlarm(Date.now() + SWEEP_INTERVAL_MS);

    const members = await this.loadMembers();
    const now = Date.now();
    let changed = false;

    for (const [dev, rec] of members) {
      if (now - rec.seen > MEMBER_TTL_MS) {
        members.delete(dev);
        changed = true;
      }
    }

    // Announce lapsed pull leases so sharers stop streaming promptly, which is
    // the battery-relevant half of the watch-gate.
    if (members.size === 0) {
      await this.state.storage.delete(MEMBERS_KEY);
      return;
    }
    if (changed) await this.broadcastRoster();
  }

  // ---- handlers ---------------------------------------------------------

  private async onHello(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    if (msg.v !== PROTOCOL_VERSION) {
      ws.close(1002, "unsupported protocol");
      return;
    }

    const dev = str(msg.dev, 64);
    const group = str(msg.group, 64);
    if (!dev || !group) {
      ws.close(1002, "bad identifiers");
      return;
    }
    if (b64urlToBytes(group) === null) {
      ws.close(1002, "bad group id");
      return;
    }

    const members = await this.loadMembers();
    if (!members.has(dev) && members.size >= MAX_MEMBERS_PER_GROUP) {
      ws.close(1013, "group full");
      return;
    }

    const att: Attachment = {
      dev,
      name: str(msg.name, 64),
      pk: str(msg.pk, 256),
    };
    ws.serializeAttachment(att);

    // A reconnect resets nothing about the identity, but it does refresh
    // presence and clear any stale watch flag.
    const previous = members.get(dev);
    members.set(dev, {
      dev,
      name: att.name,
      pk: att.pk,
      // Keep a name we already had if this hello arrives empty.
      watching: previous?.watching ?? false,
      seen: Date.now(),
    });
    await this.persist(members);

    this.sendJson(ws, {
      t: "hello_ok",
      heartbeat: HEARTBEAT_SEC,
      max_frame: MAX_FRAME_BYTES,
    });
    await this.broadcastRoster();
  }

  private async onWatch(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const att = this.attachment(ws);
    if (!att.dev) return; // hello has not arrived yet

    const members = await this.loadMembers();
    const rec = members.get(att.dev);
    if (!rec) return;

    const on = Boolean(msg.on);
    if (on !== rec.watching) {
      rec.watching = on;
      rec.seen = Date.now();
      await this.persist(members);
      // The watch flag is intentionally cleartext: peers must be able to learn
      // that someone is looking, and the app is built around it.
      await this.broadcast({ t: "watch", dev: att.dev, on });
    } else {
      rec.seen = Date.now();
      await this.persist(members);
      await this.broadcastRoster();
    }
  }

  private async onPull(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const att = this.attachment(ws);
    if (!att.dev) return;

    const members = await this.loadMembers();
    const targetDev = str(msg.dev, 64);
    const ttl = Math.min(
      Math.max(Number(msg.ttl) || 0, 1),
      PULL_MAX_SEC,
    );

    if (!targetDev || targetDev === att.dev) return;
    const target = members.get(targetDev);
    if (!target) return;

    const targetWs = this.socketFor(targetDev);
    if (targetWs) {
      this.sendJson(targetWs, {
        t: "pullreq",
        from: att.dev,
        name: att.name,
        ttl,
      });
    }
    await this.broadcast({ t: "pulled", dev: targetDev, by: att.dev, ttl });
  }

  private async onGone(ws: WebSocket): Promise<void> {
    const att = this.attachment(ws);
    if (!att.dev) return;

    const members = await this.loadMembers();
    // Only drop the roster entry if this very device has no other socket.
    if (this.socketFor(att.dev, ws) === undefined) {
      members.delete(att.dev);
      await this.persist(members);
      await this.broadcastRoster();
    }
  }

  // ---- storage ----------------------------------------------------------

  private async loadMembers(): Promise<Map<string, MemberRecord>> {
    const raw = await this.state.storage.get<MemberRecord[]>(MEMBERS_KEY);
    return new Map((raw ?? []).map((r) => [r.dev, r]));
  }

  private async persist(members: Map<string, MemberRecord>): Promise<void> {
    await this.state.storage.put(MEMBERS_KEY, [...members.values()]);
  }

  private attachment(ws: WebSocket): Attachment {
    try {
      return ws.deserializeAttachment() as Attachment;
    } catch {
      return { dev: "", name: "", pk: "" };
    }
  }

  /** Live socket for a device, optionally excluding one. */
  private socketFor(dev: string, exclude?: WebSocket): WebSocket | undefined {
    for (const ws of this.state.getWebSockets()) {
      if (ws === exclude) continue;
      if (this.attachment(ws).dev === dev) return ws;
    }
    return undefined;
  }

  // ---- fanout -----------------------------------------------------------

  private async broadcastRoster(): Promise<void> {
    const members = await this.loadMembers();
    const entries: RosterEntry[] = [];
    for (const rec of members.values()) {
      entries.push({
        dev: rec.dev,
        name: rec.name,
        pk: rec.pk,
        on: this.socketFor(rec.dev) !== undefined,
        watching: rec.watching,
        seen: rec.seen,
      });
    }
    // Includes the sender; clients converge on the same roster that way, which
    // is what the Python reference does too.
    await this.broadcast({ t: "roster", members: entries });
  }

  private async broadcast(obj: unknown): Promise<void> {
    const text = JSON.stringify(obj);
    for (const ws of this.state.getWebSockets()) {
      this.send(ws, text);
    }
  }

  private send(ws: WebSocket, data: string | ArrayBuffer): void {
    try {
      ws.send(data);
    } catch {
      // Socket died between enumeration and send. The close handler will
      // clean up; nothing to do here.
    }
  }

  private sendJson(ws: WebSocket, obj: unknown): void {
    this.send(ws, JSON.stringify(obj));
  }
}

export { b64urlToBytes, toHex };