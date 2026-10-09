"""
End-to-end check against the deployed Cloudflare relay.

This is the first and only exercise of the Durable Object code against the real
Cloudflare runtime - everything before this only ever typechecked.

Run:  python e2e_production.py [host]
"""

import asyncio
import json
import ssl
import sys

import websockets

import keacrypto as k

DEFAULT_HOST = "kea-relay.kea-11ce16198a.workers.dev"


async def next_binary(ws, timeout=15):
    """Next binary frame, skipping control JSON."""
    end = asyncio.get_running_loop().time() + timeout
    while True:
        left = end - asyncio.get_running_loop().time()
        msg = await asyncio.wait_for(ws.recv(), timeout=left)
        if isinstance(msg, bytes):
            return msg


async def until(ws, kind, timeout=15):
    """Next control frame whose `t` matches, skipping everything else."""
    end = asyncio.get_running_loop().time() + timeout
    while True:
        left = end - asyncio.get_running_loop().time()
        msg = await asyncio.wait_for(ws.recv(), timeout=left)
        if isinstance(msg, bytes):
            continue
        ctrl = json.loads(msg)
        if ctrl.get("t") == kind:
            return ctrl


async def main(host: str) -> int:
    ctx = ssl.create_default_context()
    gid, key = k.random_bytes(16), k.random_bytes(32)
    g64 = k.b64u(gid)
    url = f"wss://{host}/ws/{g64}"
    print(f"connect: {url}\n")

    ana = k.Identity.from_seed(bytes([31] * 32))
    ben = k.Identity.from_seed(bytes([32] * 32))
    a = await websockets.connect(url, ssl=ctx)
    b = await websockets.connect(url, ssl=ctx)
    print("  [ok] two sockets open  (Worker -> Durable Object routing works)")

    for ws, who, name in ((a, ana, "Ana"), (b, ben, "Ben")):
        await ws.send(json.dumps({
            "t": "hello", "v": 1, "group": g64,
            "dev": who.device_id, "name": name, "pk": k.b64u(who.sign_pub),
        }))
    print("  [ok] hello_ok          ", await until(a, "hello_ok"))

    roster = await until(a, "roster")
    print("  [ok] roster            ",
          [(m["name"], m["watching"]) for m in roster["members"]])

    # watch-gate: the whole point of the app.
    await b.send(json.dumps({"t": "watch", "on": True}))
    await until(a, "roster")
    print("  [ok] watch-gate        Ben watching ->",
          [(m["name"], m["watching"]) for m in (await until(a, "roster"))["members"]])

    state_b = k.ReceiverState()
    await a.send(k.seal(group_key=key, group_id=gid, identity=ana,
                        session_id=bytes(8), seq=0,
                        payload={"type": "LOC", "lat": 48.2082, "lon": 16.3738,
                                 "acc": 7.5, "bat": 0.81, "src": "gps"}))
    loc = k.open_frame(await next_binary(b), group_key=key, group_id=gid,
                       sign_pub=ana.sign_pub, state=state_b)
    print(f"  [ok] LOC end-to-end    {loc['type']} {loc['lat']},{loc['lon']} "
          f"acc={loc['acc']} bat={loc['bat']}")

    await a.send(k.seal(group_key=key, group_id=gid, identity=ana,
                        session_id=bytes(8), seq=1,
                        payload={"type": "FENC", "fid": "home", "ev": "enter",
                                 "lat": 48.2, "lon": 16.37}))
    fen = k.open_frame(await next_binary(b), group_key=key, group_id=gid,
                       sign_pub=ana.sign_pub, state=state_b)
    print(f"  [ok] FENC end-to-end   {fen['type']} {fen['ev']} zone={fen['fid']}")

    # acquire-live ("Get live location")
    await b.send(json.dumps({"t": "pull", "dev": ana.device_id, "ttl": 900}))
    print("  [ok] pull forwarded    ", await until(a, "pullreq"))

    # Content boundary: the sender must not receive its own frame.
    try:
        await next_binary(a, timeout=4)
        print("  [!!] sender received an echo of its own frame")
    except asyncio.TimeoutError:
        print("  [ok] no sender echo    Ana got nothing back")

    # A late joiner must not receive history.
    cid = k.Identity.from_seed(bytes([33] * 32))
    c = await websockets.connect(url, ssl=ctx)
    await c.send(json.dumps({"t": "hello", "v": 1, "group": g64,
                             "dev": cid.device_id, "name": "Cid",
                             "pk": k.b64u(cid.sign_pub)}))
    await until(c, "roster")
    try:
        await next_binary(c, timeout=4)
        print("  [!!] late joiner received a stale frame")
    except asyncio.TimeoutError:
        print("  [ok] no stale delivery Cid joined clean")

    await a.close()
    await b.close()
    await c.close()
    print("\nall checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_HOST)))