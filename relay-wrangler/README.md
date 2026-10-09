# KEA relay — Cloudflare Workers + Durable Objects

The relay, ported from `../relay/relay.py`. Same wire protocol, same content
boundary, better deployment story.

## Why this instead of a VM

| | VM (Oracle/Hetzner) | Durable Objects |
|---|---|---|
| Payment card needed | Oracle: **yes** | **no** |
| Sleeps when idle | no | no |
| Idle reclaim risk | Oracle has reclaimed idle Always Free instances | none — there is no VM |
| Horizontal scaling | **no**, `--max-instances 1` required | **yes**, one object per group |
| Cost | $0 / €4 per month | free tier |

The scaling difference is the real win. `relay.py` keeps every group in one
process's heap, so a second instance would silently split a group in half and
nobody would reach anybody. Here each group is an isolated Durable Object, so
the Worker scales out and one busy group cannot stall another.

## Deploy

```bash
npm install
npx wrangler login
npx wrangler deploy
```

Wrangler prints something like:

```
https://kea-relay.<your-account>.workers.dev
```

In the app: **Settings → Relay server** → enter the host:

```
wss://kea-relay.<your-account>.workers.dev
```

The client appends `/ws/<groupId>` itself. You do **not** include the path.

> **Wrangler needs a Node build for your platform.** Cloudflare's local runtime
> (`workerd`) ships no `win-arm64` build, so `wrangler dev` and therefore
> `wrangler install` fail on Windows-on-ARM. Deploy from any x64/x86 machine, or
> from CI, or connect the repo to Cloudflare's Git integration and deploy from
> the dashboard. `npm run typecheck` works everywhere.

## Endpoints

```
GET /                      service banner
GET /health                liveness, returns {"ok":true,...}
GET /ws/<base64url groupId>  WebSocket upgrade
```

The group id is in the **path** rather than the query string so the 16-byte key
never lands in a query log. `relay.py` accepts the same shape and simply
ignores the extra segment, which means one URL setting works against either
backend.

## Durability model

WebSockets use the **Hibernation API** (`acceptWebSocket` +
`webSocketMessage`/`webSocketClose`/`webSocketError`):

- Sockets survive object eviction, so an idle group costs roughly nothing and
  never cold-starts.
- Per-socket identity lives in `serializeAttachment`; the roster lives in
  storage. Both survive eviction, so state is rebuilt correctly on wake.
- The sweep runs on a Durable Object **alarm**, not `setInterval` — intervals do
  not exist in this runtime.

## Content boundary

`src/room.ts` is the only place that touches payload bytes, and all it does is
`send()`. It never parses, decodes, logs, or buffers an opaque frame. A
compromised Worker would learn who is in which group, when, and who is
watching — and not a single coordinate.

## Testing

```bash
npm run typecheck
```

The end-to-end conformance suite still lives in `../relay` and runs against the
Python reference:

```bash
cd ../relay
python -m pip install -r requirements.txt -r requirements-dev.txt
python test_protocol.py        # 24 checks
```

That suite is the contract. If the TypeScript relay and the Python reference
ever disagree, the clients break — so keep both in step.

## Known gaps

- **No authentication.** Possession of a group id is enough to join and to read
  roster metadata. Same as the Python relay. See `../PROTOCOL.md` §11.
- **Durable Object storage is SQLite-backed and billable above the free tier.**
  A family group is nowhere near it, but a busy public deployment would need a
  look.
- **Not load-tested against real Cloudflare.** Typechecked and unit-tested
  locally; the Durable Object handlers have not been exercised against the live
  runtime, because `workerd` cannot run on Windows-on-ARM.