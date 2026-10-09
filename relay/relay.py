"""
KEA relay — a content-blind, end-to-end-encrypted location fanout relay.

This process holds no keys and cannot read a coordinate. It routes opaque blobs
by group id and brokers presence. See ../PROTOCOL.md for the normative spec.

Design rules that must not be violated:
  * Never parse, decode, log, or inspect the contents of a binary payload.
  * Never write payload bytes to disk, stdout, or an exception message.
  * If you cannot route something, drop it. Do not buffer it "for later".

Runs on Python 3.11+. No state on disk: a restart empties every room, which is
correct — clients re-announce themselves on reconnect.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import signal
import time
from typing import Any

from aiohttp import WSMsgType, web

# --------------------------------------------------------------------------
# Constants (mirrors PROTOCOL.md §10)
# --------------------------------------------------------------------------

MAX_FRAME_BYTES = 16_384          # PROTOCOL.md MAX_FRAME_BYTES
HANDSHAKE_TIMEOUT_S = 15          # must send `hello` within this or be dropped
HEARTBEAT_SEC = 25                # PROTOCOL.md HEARTBEAT_SEC — client ping cadence
MEMBER_TTL_S = 90                 # drop members not seen for this long
SWEEP_INTERVAL_S = 15
MAX_MEMBERS_PER_GROUP = 512
MAX_GROUPS = 20_000
MAX_CONNECTIONS = 10_000

log = logging.getLogger("kea.relay")

# --------------------------------------------------------------------------
# State
# --------------------------------------------------------------------------


class Member:
    __slots__ = ("dev", "name", "pk", "watching", "seen", "ws", "leases")

    def __init__(self, dev: str, name: str, pk: str) -> None:
        self.dev = dev
        self.name = name
        self.pk = pk
        self.watching = False
        self.seen = time.monotonic()
        self.ws: web.WebSocketResponse | None = None
        # requester_device_id -> monotonic deadline. A granted "pull live
        # location" window. Purely a timer; the relay never sees the data.
        self.leases: dict[str, float] = {}

    def roster(self) -> dict[str, Any]:
        return {
            "dev": self.dev,
            "name": self.name,
            "pk": self.pk,
            "on": self.ws is not None,
            "watching": self.watching,
            "seen": int(time.time() * 1000),
        }


class Group:
    __slots__ = ("members",)

    def __init__(self) -> None:
        self.members: dict[str, Member] = {}


# In-memory only. Reload-safe by construction: nothing here is authoritative.
GROUPS: dict[str, Group] = {}
CONNECTIONS = 0
_lock = asyncio.Lock()


def _peer_key(request: web.Request) -> str:
    """Best-effort client identity, used only for abuse logging and caps."""
    fwd = request.headers.get("X-Forwarded-For", "")
    if fwd:
        return fwd.split(",")[0].strip()
    peer = request.transport.get_extra_info("peername") if request.transport else None
    return peer[0] if peer else "unknown"


def _drop_group_if_empty(gid: str) -> None:
    g = GROUPS.get(gid)
    if g is not None and not g.members:
        del GROUPS[gid]


# --------------------------------------------------------------------------
# Fanout
# --------------------------------------------------------------------------


async def _send_json(ws: web.WebSocketResponse, obj: dict[str, Any]) -> None:
    with contextlib.suppress(ConnectionResetError, RuntimeError):
        await ws.send_str(json.dumps(obj, separators=(",", ":")))


async def _broadcast(group: Group, obj: dict[str, Any], *, exclude: str | None = None) -> None:
    """Send a control message to every live member of a group.

    Includes the sender: clients must tolerate seeing their own device id
    echoed back, which keeps the roster trivially convergent.
    """
    dead: list[str] = []
    for dev, m in group.members.items():
        if dev == exclude or m.ws is None:
            continue
        if m.ws.closed:
            dead.append(dev)
            continue
        await _send_json(m.ws, obj)
    for dev in dead:
        group.members.pop(dev, None)
    _drop_group_if_empty(group)


async def _broadcast_roster(group: Group) -> None:
    await _broadcast(
        group,
        {
            "t": "roster",
            "members": [m.roster() for m in group.members.values()],
        },
    )


# --------------------------------------------------------------------------
# Lease accounting
# --------------------------------------------------------------------------


# --------------------------------------------------------------------------
# Handlers
# --------------------------------------------------------------------------


async def health(request: web.Request) -> web.Response:
    """Unauthenticated healthcheck for the hosting platform's probe."""
    return web.json_response(
        {
            "ok": True,
            "groups": len(GROUPS),
            "members": sum(len(g.members) for g in GROUPS.values()),
            "connections": CONNECTIONS,
        }
    )


async def ws_handler(request: web.Request) -> web.WebSocketResponse:
    global CONNECTIONS

    ws = web.WebSocketResponse(heartbeat=None, max_msg_size=MAX_FRAME_BYTES)
    await ws.prepare(request)

    async with _lock:
        CONNECTIONS += 1
        if CONNECTIONS > MAX_CONNECTIONS:
            CONNECTIONS -= 1
            log.warning("connection cap hit, rejecting %s", _peer_key(request))
            await ws.close(code=1013, message=b"server busy")
            return ws

    member: Member | None = None
    gid: str | None = None

    try:
        # ---- handshake -------------------------------------------------
        try:
            first = await asyncio.wait_for(ws.receive(), timeout=HANDSHAKE_TIMEOUT_S)
        except asyncio.TimeoutError:
            await ws.close(code=1002, message=b"handshake timeout")
            return ws

        if first.type is not WSMsgType.TEXT:
            await ws.close(code=1002, message=b"expected hello")
            return ws

        try:
            hello = json.loads(first.data)
        except (ValueError, TypeError):
            await ws.close(code=1002, message=b"bad json")
            return ws

        if hello.get("t") != "hello" or hello.get("v") != 1:
            await ws.close(code=1002, message=b"unsupported protocol")
            return ws

        gid = str(hello.get("group") or "")
        dev = str(hello.get("dev") or "")
        if not gid or not dev or len(gid) > 64 or len(dev) > 64:
            await ws.close(code=1002, message=b"bad identifiers")
            return ws

        group = GROUPS.setdefault(gid, Group())
        if len(group.members) >= MAX_MEMBERS_PER_GROUP:
            log.warning("group %s… at member cap", gid[:8])
            await ws.close(code=1013, message=b"group full")
            return ws

        member = Member(dev, str(hello.get("name") or "")[:64], str(hello.get("pk") or "")[:256])
        member.ws = ws
        group.members[dev] = member
        log.info("join group=%s… dev=%s (%d online)", gid[:8], dev, len(group.members))

        await _send_json(ws, {"t": "hello_ok", "heartbeat": HEARTBEAT_SEC, "max_frame": MAX_FRAME_BYTES})
        await _broadcast_roster(group)

        # ---- message loop ----------------------------------------------
        async for msg in ws:
            if msg.type is WSMsgType.BINARY:
                # ---- CONTENT-BOUNDARY ---------------------------------
                # Opaque. Forwarded verbatim. The relay does not know what a
                # byte means and must not attempt to learn.
                member.seen = time.monotonic()
                if len(msg.data) > MAX_FRAME_BYTES:
                    continue
                dead = []
                for other_dev, other in group.members.items():
                    if other_dev == dev or other.ws is None or other.ws.closed:
                        if other.ws is not None and other.ws.closed:
                            dead.append(other_dev)
                        continue
                    with contextlib.suppress(ConnectionResetError, RuntimeError):
                        await other.ws.send_bytes(msg.data)
                for d in dead:
                    group.members.pop(d, None)
                if dead:
                    _drop_group_if_empty(group)
                continue

            if msg.type is WSMsgType.TEXT:
                member.seen = time.monotonic()
                try:
                    ctrl = json.loads(msg.data)
                except (ValueError, TypeError):
                    continue
                if not isinstance(ctrl, dict):
                    continue

                t = ctrl.get("t")

                if t == "watch":
                    # The watch-gate signal. Deliberately cleartext: members
                    # must be able to learn that someone is watching.
                    on = bool(ctrl.get("on"))
                    if on != member.watching:
                        member.watching = on
                        await _broadcast(group, {"t": "watch", "dev": dev, "on": on})
                    else:
                        await _broadcast_roster(group)

                elif t == "pull":
                    # "Acquire live location": ask one member to stream for a
                    # bounded window even though nobody has the map open.
                    target_dev = str(ctrl.get("dev") or "")
                    ttl = max(1, min(int(ctrl.get("ttl") or 900), 24 * 3600))
                    target = group.members.get(target_dev)
                    if target is None or target_dev == dev:
                        continue
                    target.leases[dev] = time.monotonic() + ttl
                    if target.ws is not None:
                        await _send_json(
                            target.ws,
                            {"t": "pullreq", "from": dev, "name": member.name, "ttl": ttl},
                        )
                    await _broadcast(
                        group,
                        {"t": "pulled", "dev": target_dev, "by": dev, "ttl": ttl},
                    )

                elif t == "leave":
                    break

                elif t == "ping":
                    await _send_json(ws, {"t": "pong", "ts": ctrl.get("ts")})

    except asyncio.CancelledError:
        raise
    except Exception:
        log.exception("connection failed (group=%s dev=%s)", (gid or "?")[:8], (member.dev if member else "?")[:11])
    finally:
        async with _lock:
            CONNECTIONS -= 1
        if gid and member is not None:
            group = GROUPS.get(gid)
            if group is not None:
                group.members.pop(member.dev, None)
                _drop_group_if_empty(gid)
                await _broadcast_roster(group)
            log.info("left group=%s… dev=%s", gid[:8], member.dev)

    return ws


# --------------------------------------------------------------------------
# Housekeeping
# --------------------------------------------------------------------------


async def sweeper(stop: asyncio.Event) -> None:
    """Drop silent members and announce lapsed pull-leases.

    Without this a phone that vanishes without a close frame would keep a
    `watching=true` flag forever, and sharers would keep streaming forever —
    the exact battery bug the watch-gate exists to prevent.
    """
    while not await _sleep_or_stop(stop, SWEEP_INTERVAL_S):
        now = time.monotonic()
        swept: list[Group] = []
        lapsed: list[tuple[Group, str]] = []

        for gid in list(GROUPS):
            group = GROUPS[gid]
            stale = [d for d, m in group.members.items() if now - m.seen > MEMBER_TTL_S]
            if stale:
                for d in stale:
                    group.members.pop(d, None)
                swept.append(group)
                log.info("swept %d stale member(s) from group=%s…", len(stale), gid[:8])

            # Announce lease expiry so sharers stop streaming the moment the
            # viewer that asked for a live position goes quiet.
            for m in list(group.members.values()):
                if now - m.seen <= MEMBER_TTL_S and _expire_one(m):
                    lapsed.append((group, m.dev))

            if not group.members:
                del GROUPS[gid]

        for group, dev in lapsed:
            await _broadcast(group, {"t": "pullexpired", "dev": dev})

        for group in swept:
            await _broadcast_roster(group)


async def _sleep_or_stop(stop: asyncio.Event, seconds: float) -> bool:
    """Sleep, but return True immediately if asked to stop."""
    try:
        await asyncio.wait_for(stop.wait(), timeout=seconds)
        return True
    except asyncio.TimeoutError:
        return False


def _expire_one(m: Member) -> bool:
    """Drop lapsed leases for one member. True if at least one lapsed."""
    now = time.monotonic()
    gone = [req for req, dl in m.leases.items() if dl <= now]
    for req in gone:
        del m.leases[req]
    return bool(gone)


async def reaper(stop: asyncio.Event) -> None:
    """Backstop for the (unlikely) case of unbounded group creation."""
    while not await _sleep_or_stop(stop, 60):
        if len(GROUPS) <= MAX_GROUPS:
            continue
        now = time.monotonic()
        emptiest = sorted(GROUPS, key=lambda g: len(GROUPS[g].members))
        for gid in emptiest[: len(GROUPS) - MAX_GROUPS]:
            group = GROUPS[gid]
            if all(now - m.seen > MEMBER_TTL_S for m in group.members.values()):
                del GROUPS[gid]


def build_app() -> web.Application:
    """Build the app.

    Background tasks are tracked and cancelled on cleanup. An orphaned sweeper
    holds a reference to the app and makes shutdown hang, which is exactly the
    kind of bug that only shows up in tests.
    """
    app = web.Application()
    app.router.add_get("/health", health)

    # `/ws/<groupId>` and bare `/ws` are both accepted.
    #
    # The group id in the path exists for the Cloudflare Durable Object port:
    # a Worker must choose the object *before* the WebSocket upgrade, so it
    # cannot wait for the group id in the `hello` frame. This relay routes by
    # the group field of `hello`, so it simply ignores the extra segment and
    # both backends can be pointed at by one URL.
    app.router.add_get("/ws", ws_handler)
    app.router.add_get("/ws/{tail:.*}", ws_handler)

    app.router.add_get("/", lambda _r: web.json_response({"service": "kea-relay", "v": 1}))

    async def _startup(a: web.Application) -> None:
        stop = asyncio.Event()
        a["stop"] = stop
        a["tasks"] = [
            asyncio.create_task(sweeper(stop), name="kea-sweeper"),
            asyncio.create_task(reaper(stop), name="kea-reaper"),
        ]

    async def _cleanup(a: web.Application) -> None:
        a["stop"].set()
        for t in a["tasks"]:
            t.cancel()
        await asyncio.gather(*a["tasks"], return_exceptions=True)

    app.on_startup.append(_startup)
    app.on_cleanup.append(_cleanup)
    return app


def main() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    # Free hosts hand us PORT; locally default to 8080.
    port = int(os.environ.get("PORT", "8080"))
    # HOST lets a deployment bind loopback-only when a tunnel (Cloudflare, or a
    # reverse proxy on the same box) is the only thing that should reach it.
    host = os.environ.get("HOST", "0.0.0.0")
    log.info("kea relay listening on %s:%d", host, port)
    web.run_app(
        build_app(),
        host=host,
        port=port,
        access_log=None,   # never log query strings or payload details
        print=None,
    )


# Module-level factory for platforms that import the app (gunicorn, aiohttp CLI).
app = build_app


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass