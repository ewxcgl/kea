# Deploying the relay

The relay is ~40 MB of RAM and holds nothing on disk. It is designed to be
evicted without ceremony: every client re-announces itself on reconnect, and
the Android client reconnects with exponential backoff **and full jitter** so a
whole group does not stampede a cold instance.

That is why **scale-to-zero is acceptable here**. A sleeping instance costs
nothing and costs one slow first connection.

---

## What you need first

The container image:

```bash
cd relay
docker build -t kea-relay .
docker run --rm -p 8080:8080 kea-relay        # should answer /health
```

If `docker build` fails, the deploy will fail. Fix that first.

---

## Option A — Google Cloud Run (recommended: free tier, no VM, no card spend)

Scales to zero. Needs a Google account.

```bash
pip install gcloud            # or: winget install Google.CloudSDK
gcloud auth login

gcloud run deploy kea-relay \
  --source . \
  --region europe-west3 \
  --allow-unauthenticated \
  --min-instances 0 \
  --max-instances 1 \
  --cpu 1 --memory 256Mi \
  --timeout 3600 \
  --set-env-vars PORT=8080
```

Then:

```bash
gcloud run services describe kea-relay --region europe-west3 --format='value(status.url)'
```

Take the printed `https://kea-relay-xxxx-uc.a.run.app` and append `/ws`:

```
wss://kea-relay-xxxx-uc.a.run.app/ws
```

Paste that into **KEA → Settings → Relay server**.

**Why this one.** The free tier includes 2 million requests/month and enough
always-free CPU and memory that the bill stays at zero for a family group. It
runs in an EU region. There is no machine to keep alive, which is exactly the
requirement.

Caveats: `--max-instances 1` is **required**, not advisory. The relay keeps
group membership in process memory, so two instances would silently split the
group in half and nobody would connect to anybody. Scale it out only after
moving the registry to Redis pub/sub plus sticky routing.

---

## Option B — Oracle Cloud Always Free (free today, but read the caveat)

The only option here that never sleeps. Also the most painful to sign up for:
Oracle frequently rejects new accounts with "insufficient capacity" — retry a
few times, or pick a quieter region.

**Honest caveat on "forever."** Always Free is a marketing term, not a
contract. Oracle has already run reclamation waves against idle Always Free
instances, and a family location relay is *exactly* that profile — idle most of
the time. Treat the free tier as "free until Oracle changes its mind".

What makes that far less scary than it sounds: **losing the relay costs you
nothing.** It is stateless, writes nothing to disk, and every client reconnects
on its own with jittered backoff. Recreate the VM elsewhere, paste the same
`wss://` URL in Settings, done.

Shape: `VM.Standard.A1.Flex`, 4 OCPU / 24 GB. The relay needs ~40 MB.

### Deploy

You must do these yourself — they need your Oracle account:

1. Create the VM in a home region, Ubuntu 24.04 ARM.
2. Optionally create a free DuckDNS subdomain.
3. SSH in and run:

```bash
# copy the relay up, then:
bash bootstrap-oracle.sh          # installs /opt/kea + hardened systemd unit
bash tls-setup.sh cloudflare      # stable URL, zero inbound ports (needs a domain)
bash tls-setup.sh duckdns         # free subdomain, needs 80+443 open
```

Then paste the `wss://…/ws` URL into **KEA → Settings → Relay server**, or
rebuild with `-PvigilRelayUrl=wss://…`.

### Why it binds to loopback only

`bootstrap-oracle.sh` sets `HOST=127.0.0.1`. The relay answers only on the
loopback interface, so nothing on the network can reach it directly — TLS
termination is a separate process.

With the **Cloudflare** path you can leave *every* inbound port closed in the
Oracle security list, because the tunnel dials outbound to Cloudflare's edge.
That is the smallest possible attack surface, and it is why that path is
recommended if you own a domain.

With the **DuckDNS** path ports 80 and 443 must be open, because Caddy answers
the ACME challenge directly. Fully free, no domain purchase.

### What the systemd unit locks down

`NoNewPrivileges`, `ProtectSystem=strict`, `PrivateDevices`,
`RestrictAddressFamilies` down to `AF_INET/AF_INET6/AF_UNIX`, an **empty**
capability bounding set, `MemoryDenyWriteExecute`, `@system-service` syscall
filtering, `MemoryMax=256M`, and `CPUQuota=50%`. The relay needs to read one
directory, open one socket and write nothing; everything else is refused by the
kernel rather than trusted.

---

## Option C — Render / Koyeb (free, but they sleep)

`render.yaml` and `Procfile` are already in this directory. Render's free tier
sleeps after 15 minutes idle and then takes 30–60 seconds to wake, which means
the first watch of the day may hang on "Connecting…". The clients recover on
their own, but it looks broken to a user who has not seen the app before.

Use these if you want zero setup and can accept the cold start. Otherwise
prefer Option A.

Fly.io is deliberately not recommended: it removed its blanket free allowance
for new organisations, so it now needs a card.

---

## After deploying

1. **Settings → Relay server** → paste `wss://…/ws`. It is saved locally and
   applied immediately.
2. Or rebuild with the URL baked in:
   ```bash
   ./gradlew assembleRelease -PvigilRelayUrl=wss://your-host/ws
   ```
3. Confirm it is alive:
   ```bash
   curl https://your-host/health
   # {"ok":true,"groups":0,"members":0,"connections":0}
   ```
   `members` climbing above zero means real devices are connected.

---

## Things worth knowing before you rely on it

- **No authentication.** Possession of a group id is enough to join and to see
  roster metadata. See PROTOCOL.md §11. An invite-issued capability token is
  the highest-value missing feature.
- **Plain `ws://` on a public host** means anyone on the path can see the
  routing (who is in which group, who is watching) — but still not a single
  coordinate. Use `wss://` on anything but a private LAN. The app does not yet
  enforce this; PROTOCOL.md states the intent.
- **Do not run more than one instance.** There is no shared state.
- **Nothing is persisted.** A restart empties every room, which is correct:
  clients re-announce. Positions are never written to disk, by the relay or by
  the app.