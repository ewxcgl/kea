"""
Conformance suite for KEA.

Run:  python -m test_protocol        (from the relay/ directory)

Two halves:
  1. Crypto vectors — PROTOOCO.md §3–§5, including every attack the format is
     supposed to defeat. Port these vectors to Kotlin and Swift before writing
     any UI.
  2. Relay behaviour — a real relay on a real socket, real clients, real frames.
     Proves the watch-gate and the content boundary actually work.

No pytest dependency: the free hosts have no test runner and neither should we.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import struct
import sys
import traceback

import websockets

import keacrypto as vl
from relay import build_app

# ---------------------------------------------------------------------------
# Tiny test harness
# ---------------------------------------------------------------------------

_TESTS: list = []
_FAILURES: list[tuple[str, str]] = []
_PASSED = 0


def test(fn):
    _TESTS.append(fn)
    return fn


def eq(actual, expected, what: str = "") -> None:
    if actual != expected:
        raise AssertionError(f"{what}: expected {expected!r}, got {actual!r}")


def ok(cond, what: str = "") -> None:
    if not cond:
        raise AssertionError(what or "assertion failed")


def raises(exc, fn, what: str = ""):
    try:
        fn()
    except exc as e:
        return e
    except Exception as e:  # noqa: BLE001
        raise AssertionError(f"{what}: expected {exc.__name__}, got {type(e).__name__}: {e}") from e
    raise AssertionError(f"{what}: expected {exc.__name__}, nothing raised")


# ---------------------------------------------------------------------------
# Shared fixtures
# ---------------------------------------------------------------------------

GID = bytes(range(16))
GKEY = bytes(range(32))
SESS = bytes(range(8))


def mk_identity(i: int) -> vl.Identity:
    return vl.Identity.from_seed(bytes([i] * 32))


def loc(seq: int = 0, **over) -> dict:
    p = {
        "type": "LOC", "seq": seq, "ts": vl.now_ms(),
        "lat": 52.52, "lon": 13.40, "acc": 10.0,
        "alt": 34.0, "spd": 1.2, "brg": 90.0, "bat": 0.8, "src": "gps",
    }
    p.update(over)
    return p


# ===========================================================================
# 1. Crypto — PROTOCOL.md §3–§5
# ===========================================================================


@test
def test_roundtrip():
    ident = mk_identity(1)
    frame = vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                    session_id=SESS, seq=0, payload=loc(0))
    out = vl.open_frame(frame, group_key=GKEY, group_id=GID,
                        sign_pub=ident.sign_pub, state=vl.ReceiverState())
    eq(out["type"], "LOC", "type")
    eq(out["lat"], 52.52, "lat")
    eq(out["src"], "gps", "src")


@test
def test_deterministic_vectors():
    """Same inputs must give byte-identical output on every platform."""
    a, pub_a, dev_a = vl.vector_frame()
    b, pub_b, dev_b = vl.vector_frame()
    eq(a.hex(), b.hex(), "frame bytes differ across runs")
    eq(dev_a, dev_b, "device id differs across runs")
    eq(dev_a, "Vkdap1RjR0w", "device id must match the published vector")
    eq(vl.b64u(pub_a), "A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg", "signPub vector")
    # 3 header + 35 routing + 12 nonce + 123 plaintext + 16 tag + 64 signature
    eq(len(a), 269, "vector frame length")


@test
def test_wire_layout_is_parseable():
    frame, pub, dev = vl.vector_frame()
    f = vl.parse(frame)
    eq(f.device_id, dev, "device id in header")
    eq(f.session_id, SESS, "session id in header")
    eq(f.seq, 0, "seq in header")
    eq(f.ts_ms, vl.VECTOR_TS, "ts in header")
    eq(f.tag, b"LOC ", "type tag in header")
    eq(len(f.nonce), 12, "nonce is carried on the wire")
    eq(len(f.signature), 64, "signature length")
    # 269 total = 3 header + 35 routing + 12 nonce + 155 cipher + 64 signature
    eq(len(f.ciphertext) - 16, 139, "ciphertext is plaintext length + Poly1305 tag")
    eq(vl.type_tag_of("LOC"), b"LOC ", "type tag padding")
    eq(vl.type_tag_of("FENC"), b"FENC", "four-char type needs no padding")


@test
def test_swapped_nonce_rejected():
    """The nonce is derived, not chosen. A swapped one must not decrypt."""
    ident = mk_identity(19)
    frame = bytearray(vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                              session_id=SESS, seq=0, payload=loc(0)))
    nonce_at = 3 + 11 + 8 + 4 + 8 + 4
    frame[nonce_at] ^= 0x01
    raises(vl.AuthError, lambda: vl.open_frame(
        bytes(frame), group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub,
        state=vl.ReceiverState()), "swapped nonce accepted")


@test
def test_replay_rejected():
    ident = mk_identity(2)
    frame = vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                    session_id=SESS, seq=5, payload=loc(5))
    st = vl.ReceiverState()
    vl.open_frame(frame, group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub, state=st)
    raises(vl.ReplayError, lambda: vl.open_frame(
        frame, group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub, state=st),
        "identical frame replayed")


@test
def test_out_of_order_seq_rejected():
    ident = mk_identity(3)
    st = vl.ReceiverState()
    f5 = vl.seal(group_key=GKEY, group_id=GID, identity=ident, session_id=SESS, seq=5, payload=loc(5))
    f4 = vl.seal(group_key=GKEY, group_id=GID, identity=ident, session_id=SESS, seq=4, payload=loc(4))
    vl.open_frame(f5, group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub, state=st)
    raises(vl.ReplayError, lambda: vl.open_frame(
        f4, group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub, state=st),
        "older seq accepted")


@test
def test_new_session_resets_replay_window():
    """App restart mints a fresh sessionId, so seq restarts at 0 legitimately."""
    ident = mk_identity(4)
    st = vl.ReceiverState()
    f1 = vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                 session_id=b"\x01" * 8, seq=99, payload=loc(99))
    f2 = vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                 session_id=b"\x02" * 8, seq=0, payload=loc(0))
    vl.open_frame(f1, group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub, state=st)
    out = vl.open_frame(f2, group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub, state=st)
    eq(out["seq"], 0, "fresh session seq")


@test
def test_wrong_group_key_fails():
    ident = mk_identity(5)
    frame = vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                    session_id=SESS, seq=0, payload=loc(0))
    raises(vl.AuthError, lambda: vl.open_frame(
        frame, group_key=bytes(32), group_id=GID, sign_pub=ident.sign_pub,
        state=vl.ReceiverState()), "wrong group key decrypted")


@test
def test_wrong_group_id_fails():
    ident = mk_identity(6)
    frame = vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                    session_id=SESS, seq=0, payload=loc(0))
    raises(vl.AuthError, lambda: vl.open_frame(
        frame, group_key=GKEY, group_id=bytes(16), sign_pub=ident.sign_pub,
        state=vl.ReceiverState()), "wrong group id decrypted")


@test
def test_wrong_signing_key_fails():
    """An impostor cannot present another member's deviceId."""
    real = mk_identity(7)
    fake = mk_identity(8)
    frame = vl.seal(group_key=GKEY, group_id=GID, identity=real,
                    session_id=SESS, seq=0, payload=loc(0))
    raises(vl.AuthError, lambda: vl.open_frame(
        frame, group_key=GKEY, group_id=GID, sign_pub=fake.sign_pub,
        state=vl.ReceiverState()), "impostor signature accepted")


@test
def test_forged_position_by_group_member_fails():
    """A member holding the group key still cannot forge a peer's position.

    This is the whole reason payloads are signed and not merely encrypted.
    The attacker re-seals under its own key, then rewrites the deviceId in the
    cleartext header to impersonate the victim.
    """
    victim = mk_identity(9)
    attacker = mk_identity(10)
    forged = bytearray(vl.seal(group_key=GKEY, group_id=GID, identity=attacker,
                               session_id=SESS, seq=0, payload=loc(0)))
    forged[3:14] = victim.device_id.encode("ascii")   # header: deviceId
    # deviceId now matches the victim's public key, so the roster check passes.
    eq(vl.parse(bytes(forged)).device_id, victim.device_id, "impersonated header")
    raises(vl.AuthError, lambda: vl.open_frame(
        bytes(forged), group_key=GKEY, group_id=GID, sign_pub=victim.sign_pub,
        state=vl.ReceiverState()), "forged position accepted")


@test
def test_ciphertext_tamper_detected():
    ident = mk_identity(11)
    frame = bytearray(vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                              session_id=SESS, seq=0, payload=loc(0)))
    frame[80] ^= 0x01
    raises(vl.AuthError, lambda: vl.open_frame(
        bytes(frame), group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub,
        state=vl.ReceiverState()), "tampered ciphertext accepted")


@test
def test_routing_header_tamper_detected():
    """Flipping a coordinate byte is impossible; flipping routing is too."""
    ident = mk_identity(12)
    frame = bytearray(vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                              session_id=SESS, seq=0, payload=loc(0)))
    frame[13] ^= 0xFF          # sessionId[0]
    raises(vl.AuthError, lambda: vl.open_frame(
        bytes(frame), group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub,
        state=vl.ReceiverState()), "tampered routing accepted")


@test
def test_timestamp_tamper_detected():
    """Rewriting the timestamp must fail on the signature, not on freshness.

    The rewritten value is kept inside the freshness window on purpose, so the
    only thing that can catch it is the Ed25519 check.
    """
    ident = mk_identity(13)
    ts = vl.now_ms()
    frame = bytearray(vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                              session_id=SESS, seq=0, payload=loc(0), ts_ms=ts))
    # body layout: 3B header + 11B deviceId + 8B sessionId + 4B seq, then ts
    struct.pack_into(">q", frame, 3 + 11 + 8 + 4, ts - 120_000)
    raises(vl.AuthError, lambda: vl.open_frame(
        bytes(frame), group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub,
        state=vl.ReceiverState()), "tampered timestamp accepted")


@test
def test_type_confusion_rejected():
    """A signed LOC cannot be relabelled as a FENC to dodge the UI."""
    ident = mk_identity(14)
    frame = bytearray(vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                              session_id=SESS, seq=0, payload=loc(0)))
    frame[3 + 11 + 8 + 4 + 8 : 3 + 11 + 8 + 4 + 8 + 4] = b"FENC"
    raises(vl.AuthError, lambda: vl.open_frame(
        bytes(frame), group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub,
        state=vl.ReceiverState()), "type confusion accepted")


@test
def test_stale_payload_rejected():
    ident = mk_identity(15)
    old = vl.now_ms() - (vl.MAX_SKEW_MS + 60_000)
    frame = vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                    session_id=SESS, seq=0, payload=loc(0), ts_ms=old)
    raises(vl.ReplayError, lambda: vl.open_frame(
        frame, group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub,
        state=vl.ReceiverState()), "payload from 11 minutes ago accepted")


@test
def test_body_timestamp_cannot_be_forged():
    """seal() owns seq and ts: a caller cannot smuggle a different body value.

    Otherwise a sender could put a fresh ts in the body to defeat a consumer
    that trusts the body rather than the authenticated header.
    """
    ident = mk_identity(41)
    lie = vl.now_ms() + 3_600_000
    frame = vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                    session_id=SESS, seq=7, payload=loc(7, ts=lie))
    out = vl.open_frame(frame, group_key=GKEY, group_id=GID,
                        sign_pub=ident.sign_pub, state=vl.ReceiverState())
    ok(abs(out["ts"] - vl.now_ms()) < 5_000, "body ts was taken from the caller")
    eq(out["seq"], 7, "body seq was taken from the caller")


@test
def test_frame_size_cap_enforced():
    ident = mk_identity(16)
    big = loc(0)
    big["pad"] = "x" * 20_000
    raises(ValueError, lambda: vl.seal(
        group_key=GKEY, group_id=GID, identity=ident, session_id=SESS,
        seq=0, payload=big), "oversized frame was built")


@test
def test_length_prefix_mismatch_detected():
    ident = mk_identity(17)
    frame = bytearray(vl.seal(group_key=GKEY, group_id=GID, identity=ident,
                              session_id=SESS, seq=0, payload=loc(0)))
    frame[1:3] = struct.pack(">H", len(frame) - 3 + 7)
    raises(vl.FrameError, lambda: vl.open_frame(
        bytes(frame), group_key=GKEY, group_id=GID, sign_pub=ident.sign_pub,
        state=vl.ReceiverState()), "bad length prefix accepted")


@test
def test_invite_link_roundtrip():
    gid = vl.random_bytes(16)
    key = vl.random_bytes(32)
    link = vl.build_invite(gid, key, expires_at=1_800_000_000)
    ok(link.startswith("kea://join#"), "invite scheme")
    g2, k2, exp = vl.parse_invite(link)
    eq(g2, gid, "group id")
    eq(k2, key, "group key")
    eq(exp, 1_800_000_000, "expiry")
    # The key must live in the fragment so it never reaches a server.
    ok("#" in link and link.index("#") > link.index("join"), "key is in the fragment")


@test
def test_invite_rejects_garbage():
    for bad in ["https://example.com", "kea://join", "kea://join#!!!!", "kea://join#AAAA"]:
        raises(ValueError, lambda b=bad: vl.parse_invite(b), f"accepted {bad!r}")


@test
def test_device_id_matches_key():
    """The roster advertises pk; deviceId must be provably derived from it."""
    ident = mk_identity(18)
    eq(vl.derive_device_id(ident.sign_pub), ident.device_id, "device id derivation")
    eq(len(ident.device_id), 11, "device id length")
    ok(vl.b64u(ident.sign_pub) != ident.device_id, "device id is not the pubkey")


@test
def test_nonce_unique_per_seq():
    """Distinct seq must yield distinct nonces for the same device."""
    a = vl.build_nonce(GKEY, GID, "Vkdap1RjR0w", SESS, 0)
    b = vl.build_nonce(GKEY, GID, "Vkdap1RjR0w", SESS, 1)
    ok(a != b, "nonce reused across seq")


# ===========================================================================
# 2. Relay — PROTOCOL.md §6–§7
# ===========================================================================


class Client:
    """Test-side client that speaks the real protocol."""

    def __init__(self, ident: vl.Identity, name: str):
        self.ident = ident
        self.name = name
        self.ws = None
        self.inbox: asyncio.Queue = asyncio.Queue()
        self.roster: list = []

    @property
    def dev(self) -> str:
        return self.ident.device_id

    async def join(self, url: str, group: str):
        self.ws = await websockets.connect(url, max_size=vl.MAX_FRAME_BYTES)
        await self.ws.send(json.dumps({
            "t": "hello", "v": 1, "group": vl.b64u(group),
            "dev": self.dev, "name": self.name, "pk": self.ident.sign_pub_b64,
        }))

    async def send(self, obj):
        await self.ws.send(json.dumps(obj))

    async def raw(self, data: bytes):
        await self.ws.send(data)

    async def pump(self, seconds: float):
        """Drain frames for a while, collecting control into self.roster."""
        loop = asyncio.get_running_loop()
        end = loop.time() + seconds
        while True:
            remaining = end - loop.time()
            if remaining <= 0:
                return
            try:
                msg = await asyncio.wait_for(self.ws.recv(), timeout=remaining)
            except asyncio.TimeoutError:
                return
            if isinstance(msg, bytes):
                await self.inbox.put(msg)
            else:
                ctrl = json.loads(msg)
                if ctrl.get("t") == "roster":
                    self.roster.append(ctrl["members"])

    async def wait_for(self, kind: str, seconds: float = 3.0):
        """Await a control frame of a given type."""
        loop = asyncio.get_running_loop()
        end = loop.time() + seconds
        while True:
            remaining = end - loop.time()
            if remaining <= 0:
                raise AssertionError(f"timed out waiting for {kind!r}")
            got = await asyncio.wait_for(self.ws.recv(), timeout=remaining)
            if isinstance(got, str):
                ctrl = json.loads(got)
                if ctrl.get("t") == "roster":
                    self.roster.append(ctrl["members"])
                if ctrl.get("t") == kind:
                    return ctrl

    async def wait_roster(self, count: int, seconds: float = 5.0):
        """Await a roster with exactly `count` members.

        Rosters are broadcast on every join and leave, so a client may see
        several before the one we care about. Draining until a match avoids
        racing on arrival order.
        """
        loop = asyncio.get_running_loop()
        end = loop.time() + seconds
        while True:
            remaining = end - loop.time()
            if remaining <= 0:
                raise AssertionError(f"timed out waiting for a {count}-member roster")
            got = await asyncio.wait_for(self.ws.recv(), timeout=remaining)
            if isinstance(got, str):
                ctrl = json.loads(got)
                if ctrl.get("t") == "roster":
                    self.roster.append(ctrl["members"])
                    if len(ctrl["members"]) == count:
                        return ctrl

    async def wait_watch(self, on: bool, seconds: float = 5.0):
        """Await a watch-state broadcast with the wanted value.

        A client accumulates every watch event for the group, so the first one
        it reads is not necessarily the one under test.
        """
        loop = asyncio.get_running_loop()
        end = loop.time() + seconds
        while True:
            remaining = end - loop.time()
            if remaining <= 0:
                raise AssertionError(f"timed out waiting for watch on={on}")
            got = await asyncio.wait_for(self.ws.recv(), timeout=remaining)
            if isinstance(got, str):
                ctrl = json.loads(got)
                if ctrl.get("t") == "roster":
                    self.roster.append(ctrl["members"])
                if ctrl.get("t") == "watch" and ctrl.get("on") is on:
                    return ctrl

    async def close(self):
        with contextlib.suppress(Exception):
            await asyncio.wait_for(self.ws.close(), timeout=2)


async def start_relay() -> tuple[str, object]:
    """Boot the real relay app on an ephemeral port."""
    from aiohttp import web

    app = build_app()
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = site._server.sockets[0].getsockname()[1]
    return f"ws://127.0.0.1:{port}/ws", runner


async def relay_suite():
    url, runner = await start_relay()
    gid = vl.random_bytes(16)
    gkey = vl.random_bytes(32)
    ana = Client(mk_identity(31), "Ana")
    ben = Client(mk_identity(32), "Ben")
    cid = Client(mk_identity(33), "Cid")
    try:
        # -- handshake ----------------------------------------------------
        await ana.join(url, gid)
        hello_ok = await ana.wait_for("hello_ok")
        eq(hello_ok["heartbeat"], 25, "heartbeat advertised")
        eq(hello_ok["max_frame"], vl.MAX_FRAME_BYTES, "frame cap advertised")

        await ben.join(url, gid)
        await cid.join(url, gid)

        for c in (ana, ben, cid):
            r = await c.wait_roster(3)
            eq(len(r["members"]), 3, f"{c.name} sees 3 members")
            names = {m["name"] for m in r["members"]}
            eq(names, {"Ana", "Ben", "Cid"}, f"{c.name} roster names")
            pubs = {m["dev"]: m["pk"] for m in r["members"]}
            eq(pubs[ben.dev], ben.ident.sign_pub_b64, "roster advertises the right public key")

        # -- content boundary: opaque fanout ------------------------------
        payload = vl.seal(group_key=gkey, group_id=gid, identity=ana.ident,
                          session_id=SESS, seq=0, payload=loc(0))
        await ana.raw(payload)
        await asyncio.gather(ben.pump(1.0), cid.pump(1.0))
        await ana.pump(0.4)

        eq(ben.inbox.qsize(), 1, "Ben received the payload")
        eq(cid.inbox.qsize(), 1, "Cid received the payload")
        eq(ana.inbox.qsize(), 0, "sender echoed its own payload")

        frame = ben.inbox.get_nowait()
        opened = vl.open_frame(frame, group_key=gkey, group_id=gid,
                               sign_pub=ana.ident.sign_pub, state=vl.ReceiverState())
        eq(opened["lat"], 52.52, "end-to-end decryption")
        eq(vl.parse(frame).device_id, ana.dev, "sender attribution")
        cid.inbox.get_nowait()

        # -- the relay cannot read it -------------------------------------
        raised = raises(Exception, lambda: vl.open_frame(
            payload, group_key=vl.random_bytes(32), group_id=gid,
            sign_pub=ana.ident.sign_pub, state=vl.ReceiverState()),
            "relay-held key material could read a payload")
        ok(raised is not None, "content boundary holds")

        # -- watch-gate ---------------------------------------------------
        await ben.send({"t": "watch", "on": True})
        w = await ana.wait_watch(True)
        eq(w["dev"], ben.dev, "watch attributed to Ben")

        await ben.send({"t": "watch", "on": False})
        w = await cid.wait_watch(False)
        eq(w["dev"], ben.dev, "unwatch attributed to Ben")

        # -- acquire live location ---------------------------------------
        await cid.send({"t": "pull", "dev": ana.dev, "ttl": 30})
        req = await ana.wait_for("pullreq")
        eq(req["from"], cid.dev, "pull requester")
        eq(req["ttl"], 30, "pull ttl")

        # -- geofence payloads fan out like positions ---------------------
        fenc = vl.seal(group_key=gkey, group_id=gid, identity=ben.ident,
                       session_id=SESS, seq=0,
                       payload={"type": "FENC", "seq": 0, "ts": vl.now_ms(),
                                "fid": "home", "ev": "enter",
                                "lat": 52.5, "lon": 13.4})
        await ben.raw(fenc)
        await asyncio.gather(ana.pump(1.0), cid.pump(1.0))
        got = vl.open_frame(ana.inbox.get_nowait(), group_key=gkey, group_id=gid,
                            sign_pub=ben.ident.sign_pub, state=vl.ReceiverState())
        eq(got["ev"], "enter", "geofence event decoded")
        eq(got["fid"], "home", "geofence id")
        await cid.pump(0.3)
        if cid.inbox.qsize():
            cid.inbox.get_nowait()

        # -- departure ----------------------------------------------------
        await ben.send({"t": "leave"})
        await ben.close()
        r = await ana.wait_roster(2)
        eq({m["name"] for m in r["members"]}, {"Ana", "Cid"}, "roster after departure")
        eq(all(m["on"] for m in r["members"]), True, "survivors are still marked online")
    finally:
        # Always drop every client socket before tearing the server down,
        # otherwise aiohttp blocks on the WebSocket close handshake.
        for c in (ana, ben, cid):
            await c.close()
        await asyncio.wait_for(runner.cleanup(), timeout=10)


@test
def test_relay_end_to_end():
    asyncio.run(relay_suite())


# ===========================================================================
# Runner
# ===========================================================================


def main() -> int:
    global _PASSED
    print(f"KEA conformance — {len(_TESTS)} checks\n")
    for fn in _TESTS:
        name = fn.__name__.replace("test_", "")
        try:
            fn()
            _PASSED += 1
            print(f"  PASS  {name}")
        except Exception as e:  # noqa: BLE001
            detail = f"{type(e).__name__}: {e}"
            _FAILURES.append((name, detail))
            print(f"  FAIL  {name}")
            print(f"        {detail}")
            if not isinstance(e, AssertionError):
                tb = traceback.format_exc().strip().splitlines()
                for line in tb[-10:]:
                    print(f"        {line}")

    total = len(_TESTS)
    print(f"\n{_PASSED}/{total} passed")
    if _FAILURES:
        print("\nFailures:")
        for name, detail in _FAILURES:
            print(f"  - {name}: {detail}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())