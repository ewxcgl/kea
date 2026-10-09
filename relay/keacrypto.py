"""
Reference implementation of PROTOCOL.md §3–§4 and §5.

This file is the tie-breaker. The Kotlin and Swift clients each reimplement it;
when they disagree with this file, this file is right until PROTOCOL.md changes.

Used by test_protocol.py to generate fixed vectors and to prove the relay never
needs to understand a payload.

Wire layout (PROTOCOL.md §5), all integers big-endian:

    byte  0      flags, bit0 = sealed payload
    byte  1..2   u16  body length
    body:
        deviceId    11 ascii
        sessionId    8
        seq          4   u32
        tsMs         8   i64
        typeTag      4   ascii, padded
        nonce       12   derived from (deviceId, sessionId, seq)
        ciphertext   var  ChaCha20Poly1305 ciphertext || 16-byte Poly1305 tag
        signature   64   Ed25519

The nonce is prefixed to the ciphertext because that is the "combined" layout
both platform libraries produce natively — Android's BouncyCastle wrapper below
and iOS's CryptoKit `ChaChaPoly.SealedBox.combined`. Sharing one layout removes
a whole class of porting bug. The nonce is still *derived*, never random, so a
receiver can check it matches (deviceId, sessionId, seq) before decrypting.

The 35-byte routing header ahead of it is cleartext because a receiver must pick
the right key and check replay before spending a ChaCha20 operation. It reveals
who sent a frame, when, and whether it is a position or a geofence event. It
reveals nothing about *where*. The relay does not parse it.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import struct
import time
from dataclasses import dataclass, field

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

# ---------------------------------------------------------------------------
# PROTOCOL.md constants
# ---------------------------------------------------------------------------

PAYLOAD_INFO = b"kea-payload-v1"
NONCE_INFO = b"kea-nonce-v1"
GROUP_ID_LEN = 16
SESSION_ID_LEN = 8
DEVICE_ID_LEN = 11
GROUP_KEY_LEN = 32
MAX_SKEW_MS = 600_000
MAX_FRAME_BYTES = 16_384
SIGNATURE_LEN = 64

HEADER_LEN = DEVICE_ID_LEN + SESSION_ID_LEN + 4 + 8 + 4  # 35
NONCE_LEN = 12
AEAD_TAG_LEN = 16
TAG_LEN = 4
SIGNATURE_LEN = 64

MIN_BODY = HEADER_LEN + NONCE_LEN + AEAD_TAG_LEN + SIGNATURE_LEN  # 127


class ReplayError(Exception):
    """Frame failed a replay or freshness check."""


class AuthError(Exception):
    """Signature did not verify, or the AEAD tag did not authenticate."""


class FrameError(Exception):
    """Structurally invalid frame."""


# ---------------------------------------------------------------------------
# Encoding helpers
# ---------------------------------------------------------------------------


def b64u(raw: bytes) -> str:
    """Unpadded base64url. Matches Kotlin Base64.NO_PADDING|URL_SAFE and
    Swift's .base64URLEncodedString(omittingPadding: true)."""
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def unb64u(text: str) -> bytes:
    pad = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + pad)


def random_bytes(n: int) -> bytes:
    return os.urandom(n)


def now_ms() -> int:
    return int(time.time() * 1000)


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)


def type_tag_of(payload_type: str) -> bytes:
    """'LOC' -> b'LOC '. Fixed width so the wire header is parseable."""
    return payload_type.encode("ascii")[:4].ljust(4, b" ")


def _consttime_eq(a: bytes, b: bytes) -> bool:
    """Length-safe constant-time comparison for security-relevant equality."""
    import hmac as _hmac

    return _hmac.compare_digest(a, b)


# ---------------------------------------------------------------------------
# Identity
# ---------------------------------------------------------------------------


@dataclass
class Identity:
    """A device's long-lived Ed25519 signing key.

    deviceId is derived from the public key, so it is stable, short enough to
    paste into a chat message, and publicly checkable against the roster entry.
    """

    signing_key: Ed25519PrivateKey = field(default_factory=Ed25519PrivateKey.generate)

    @property
    def sign_pub(self) -> bytes:
        return self.signing_key.public_key().public_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PublicFormat.Raw,
        )

    @property
    def sign_pub_b64(self) -> str:
        return b64u(self.sign_pub)

    @property
    def device_id(self) -> str:
        return derive_device_id(self.sign_pub)

    def sign(self, msg: bytes) -> bytes:
        return self.signing_key.sign(msg)

    @classmethod
    def from_seed(cls, seed: bytes) -> "Identity":
        return cls(Ed25519PrivateKey.from_private_bytes(seed))


def derive_device_id(sign_pub: bytes) -> str:
    return b64u(hashlib.sha256(sign_pub).digest())[:DEVICE_ID_LEN]


def verify(signature: bytes, msg: bytes, sign_pub: bytes) -> bool:
    try:
        Ed25519PublicKey.from_public_bytes(sign_pub).verify(signature, msg)
        return True
    except Exception:
        return False


# ---------------------------------------------------------------------------
# AAD
# ---------------------------------------------------------------------------


def build_aad(
    group_id: bytes,
    device_id: str,
    session_id: bytes,
    seq: int,
    ts_ms: int,
    tag: bytes,
) -> bytes:
    """Authenticated-but-not-secret header.

    Binding typeTag here means the AEAD tag authenticates it, so a signed LOC
    can never be replayed as a FENC even though the tag travels in cleartext.
    """
    return (
        PAYLOAD_INFO
        + b"\x00"
        + group_id
        + device_id.encode("ascii")
        + session_id
        + struct.pack(">I", seq)
        + struct.pack(">q", ts_ms)
        + tag
    )


def build_nonce(group_key: bytes, group_id: bytes, device_id: str, session_id: bytes, seq: int) -> bytes:
    return hkdf(
        group_key,
        group_id,
        NONCE_INFO + device_id.encode("ascii") + session_id + struct.pack(">I", seq),
        12,
    )


def build_key(group_key: bytes, group_id: bytes) -> bytes:
    return hkdf(group_key, group_id, PAYLOAD_INFO, 32)


# ---------------------------------------------------------------------------
# Seal / open
# ---------------------------------------------------------------------------


def _validate(group_key: bytes, group_id: bytes, session_id: bytes) -> None:
    if len(group_key) != GROUP_KEY_LEN:
        raise ValueError(f"group key must be {GROUP_KEY_LEN}B, got {len(group_key)}")
    if len(group_id) != GROUP_ID_LEN:
        raise ValueError(f"group id must be {GROUP_ID_LEN}B, got {len(group_id)}")
    if len(session_id) != SESSION_ID_LEN:
        raise ValueError(f"session id must be {SESSION_ID_LEN}B, got {len(session_id)}")


def seal(
    *,
    group_key: bytes,
    group_id: bytes,
    identity: Identity,
    session_id: bytes,
    seq: int,
    payload: dict,
    ts_ms: int | None = None,
) -> bytes:
    """PROTOCOL.md §4–§5. Returns the exact bytes for the wire.

    Encrypt-then-sign. The signature covers the AAD and the ciphertext, so
    neither routing fields nor the sealed type can be altered in flight.
    """
    _validate(group_key, group_id, session_id)
    if not 0 <= seq <= 0xFFFFFFFF:
        raise ValueError("seq out of range")

    device_id = identity.device_id
    tag = type_tag_of(payload["type"])
    ts = int(now_ms() if ts_ms is None else ts_ms)

    # The authenticated header is authoritative. Overwriting the body's copies
    # stops a sender from smuggling a second, unverified timestamp or sequence
    # number inside the ciphertext for a consumer to trust.
    payload = dict(payload)
    payload["seq"] = seq
    payload["ts"] = ts

    aad = build_aad(group_id, device_id, session_id, seq, ts, tag)
    plaintext = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")

    nonce = build_nonce(group_key, group_id, device_id, session_id, seq)
    ciphertext = ChaCha20Poly1305(build_key(group_key, group_id)).encrypt(
        nonce, plaintext, aad
    )
    signature = identity.sign(b"\x00" + aad + ciphertext)

    body = (
        device_id.encode("ascii")
        + session_id
        + struct.pack(">I", seq)
        + struct.pack(">q", ts)
        + tag
        + nonce
        + ciphertext
        + signature
    )
    if len(body) > MAX_FRAME_BYTES:
        raise ValueError(f"frame {len(body)}B exceeds the {MAX_FRAME_BYTES}B cap")

    return bytes([0x01]) + struct.pack(">H", len(body)) + body


@dataclass
class Frame:
    device_id: str
    session_id: bytes
    seq: int
    ts_ms: int
    tag: bytes
    nonce: bytes
    ciphertext: bytes
    signature: bytes


def parse(frame: bytes) -> Frame:
    """Structural parse only. Does no cryptography."""
    if len(frame) < 3:
        raise FrameError("frame shorter than 3B header")
    if not frame[0] & 0x01:
        raise FrameError("not a sealed payload")
    (length,) = struct.unpack(">H", frame[1:3])
    body = frame[3:]
    if len(body) != length:
        raise FrameError(f"length prefix says {length}B, body is {len(body)}B")
    if len(body) < MIN_BODY:
        raise FrameError(f"body {len(body)}B below minimum {MIN_BODY}B")

    p = 0
    device_id = body[p : p + DEVICE_ID_LEN].decode("ascii", "replace")
    p += DEVICE_ID_LEN
    session_id = body[p : p + SESSION_ID_LEN]
    p += SESSION_ID_LEN
    (seq,) = struct.unpack(">I", body[p : p + 4])
    p += 4
    (ts_ms,) = struct.unpack(">q", body[p : p + 8])
    p += 8
    tag = body[p : p + TAG_LEN]
    p += TAG_LEN
    nonce = body[p : p + NONCE_LEN]
    p += NONCE_LEN
    signature = body[-SIGNATURE_LEN:]
    ciphertext = body[p : len(body) - SIGNATURE_LEN]
    return Frame(device_id, session_id, seq, ts_ms, tag, nonce, ciphertext, signature)


@dataclass
class ReceiverState:
    """Per-group replay bookkeeping.

    Clients must confine this to one thread / one actor. Python's GIL makes it
    safe enough for tests; Kotlin and iOS serialise onto a single coroutine or
    actor respectively, which is the real requirement.
    """

    seen: dict[tuple[str, bytes], int] = field(default_factory=dict)

    def check(self, device_id: str, session_id: bytes, seq: int) -> None:
        last = self.seen.get((device_id, session_id), -1)
        if seq <= last:
            raise ReplayError(f"replayed seq {seq} <= {last}")
        self.seen[(device_id, session_id)] = seq


def open_frame(
    frame: bytes,
    *,
    group_key: bytes,
    group_id: bytes,
    sign_pub: bytes,
    state: ReceiverState,
    now: int | None = None,
) -> dict:
    """Verify then decrypt. Raises FrameError / ReplayError / AuthError.

    Callers must supply the sender's Ed25519 public key from the roster — a
    frame is never trusted on its own say-so.
    """
    _validate(group_key, group_id, b"\x00" * SESSION_ID_LEN)
    f = parse(frame)

    if derive_device_id(sign_pub) != f.device_id:
        raise AuthError("device id does not match the supplied signing key")

    ref = int(now_ms() if now is None else now)
    if abs(ref - f.ts_ms) > MAX_SKEW_MS:
        raise ReplayError(f"payload {ref - f.ts_ms}ms outside the {MAX_SKEW_MS}ms window")

    state.check(f.device_id, f.session_id, f.seq)

    aad = build_aad(group_id, f.device_id, f.session_id, f.seq, f.ts_ms, f.tag)
    if not verify(f.signature, b"\x00" + aad + f.ciphertext, sign_pub):
        raise AuthError("signature did not verify")

    # The nonce is derived, not chosen by the sender. Check it before spending
    # a decryption, so a swapped nonce is rejected as tampering.
    expected = build_nonce(group_key, group_id, f.device_id, f.session_id, f.seq)
    if not _consttime_eq(f.nonce, expected):
        raise AuthError("nonce does not match (deviceId, sessionId, seq)")

    try:
        plaintext = ChaCha20Poly1305(build_key(group_key, group_id)).decrypt(
            f.nonce, f.ciphertext, aad
        )
    except InvalidTag as e:
        # Wrong group key, or a ciphertext/AAD mismatch. Either way the
        # payload is untrusted; never let the raw exception escape.
        raise AuthError("AEAD authentication failed") from e

    try:
        payload = json.loads(plaintext)
    except ValueError as e:
        raise AuthError(f"sealed plaintext is not JSON: {e}") from e

    if type_tag_of(str(payload.get("type", ""))) != f.tag:
        raise AuthError("type tag does not match the sealed payload type")

    return payload


# ---------------------------------------------------------------------------
# Invite links (PROTOCOL.md §3)
# ---------------------------------------------------------------------------

INVITE_VERSION = 1


def build_invite(group_id: bytes, group_key: bytes, expires_at: int = 0) -> str:
    """kea://join#<b64u( version | groupId | groupKey | expiresAt )>"""
    if len(group_id) != GROUP_ID_LEN or len(group_key) != GROUP_KEY_LEN:
        raise ValueError("bad invite dimensions")
    blob = (
        bytes([INVITE_VERSION])
        + group_id
        + group_key
        + struct.pack(">q", int(expires_at))
    )
    return "kea://join#" + b64u(blob)


def parse_invite(link: str) -> tuple[bytes, bytes, int]:
    if not link.startswith("kea://join#"):
        raise ValueError("not a kea invite link")
    blob = unb64u(link.split("#", 1)[1])
    if len(blob) != 1 + GROUP_ID_LEN + GROUP_KEY_LEN + 8:
        raise ValueError(f"invite blob is {len(blob)}B, expected 57B")
    if blob[0] != INVITE_VERSION:
        raise ValueError(f"unsupported invite version {blob[0]}")
    group_id = blob[1 : 1 + GROUP_ID_LEN]
    group_key = blob[1 + GROUP_ID_LEN : 1 + GROUP_ID_LEN + GROUP_KEY_LEN]
    (expires_at,) = struct.unpack(">q", blob[-8:])
    return group_id, group_key, expires_at


# ---------------------------------------------------------------------------
# Test vectors — fixed inputs so Kotlin and Swift can assert byte equality
# ---------------------------------------------------------------------------

VECTOR_GROUP_ID = bytes(range(16))
VECTOR_GROUP_KEY = bytes(range(32))
VECTOR_SESSION = bytes(range(8))
VECTOR_TS = 1_700_000_000_000
VECTOR_LOC = {
    "type": "LOC",
    "seq": 0,
    "ts": VECTOR_TS,
    "lat": 52.520008,
    "lon": 13.404954,
    "acc": 12.5,
    "alt": 34.0,
    "spd": 1.4,
    "brg": 87.0,
    "bat": 0.63,
    "src": "gps",
}


def vector_frame() -> tuple[bytes, bytes, str]:
    """Deterministic identity so the frame bytes are reproducible.

    Returns (frame, sign_pub, device_id).
    """
    ident = Identity.from_seed(bytes(range(32)))
    frame = seal(
        group_key=VECTOR_GROUP_KEY,
        group_id=VECTOR_GROUP_ID,
        identity=ident,
        session_id=VECTOR_SESSION,
        seq=0,
        payload=dict(VECTOR_LOC),
        ts_ms=VECTOR_TS,
    )
    return frame, ident.sign_pub, ident.device_id


if __name__ == "__main__":
    f, pub, dev = vector_frame()
    print(f"deviceId : {dev}")
    print(f"signPub  : {b64u(pub)}")
    print(f"frame    : {len(f)} bytes")
    print(f"hex      : {f.hex()}")
    opened = open_frame(
        f,
        group_key=VECTOR_GROUP_KEY,
        group_id=VECTOR_GROUP_ID,
        sign_pub=pub,
        state=ReceiverState(),
        now=VECTOR_TS,
    )
    print(f"roundtrip: {opened}")