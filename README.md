# eeck

End-to-end encrypted chat. The server never sees plaintext messages and never
holds private keys — see [`SPEC.md`](./SPEC.md) for the protocol this
implementation follows.

- `./server` — Kotlin, Ktor (Netty), JDK 21.
- `./web` — Vite + React + TypeScript.

## Prerequisites

- JDK 21 is provisioned automatically by Gradle (via the Foojay toolchain
  resolver) if not already installed — no manual setup needed.
- Node.js 20+ and npm for `./web`.

## Server

```bash
./gradlew :server:run    # starts on http://localhost:3001
./gradlew test           # or: ./gradlew :server:test
```

`GET /api/health` returns `{"status":"ok"}`.

## Web

```bash
cd web
npm install
npm run dev               # starts on http://localhost:5173
```

The dev server proxies `/api/*` and `/ws` to `http://localhost:3001`, so run
the server first (or alongside).

```bash
npm run build              # type-checks and builds to web/dist
```

## Full build

```bash
./gradlew build            # server build + test
```

## Deploy with Docker

```bash
docker compose up -d --build     # http://<host>
```

That builds two images and wires them together:

```
browser ──:80──▶ web (nginx)                        server (Ktor/Netty)
                 ├── /            → built SPA
                 ├── /api/*       ──────────────────▶ :3001
                 └── /ws          ──────────────────▶ :3001   (WebSocket upgrade)
```

Everything is served from **one origin** on purpose: the client derives its
socket URL from `window.location.origin` and calls the API with relative paths,
so same-origin means no CORS and no per-deployment origin to keep in sync. The
Ktor container is not published to the host — nginx is the only way in.

`web` waits for the server's `/api/health` to pass before accepting traffic, so
the first page load can't land on a proxy whose upstream isn't listening yet.

Config is optional — copy `.env.example` to `.env` to change the published port.

### HTTPS

**Plain HTTP is not a safe way to run this.** Without TLS the chat link and all
traffic metadata are exposed on the network path, and the page itself could be
tampered with in transit.

Participant ids are the fingerprints of their keys, and the server admits a
connection only after it proves it holds that key, so no one — another
participant or the server — can announce a different key under an existing id.
What remains is that a malicious server could introduce an *entirely fake*
participant. The header shows your own id (`you: …`); comparing it with the
label your contact sees for you, over another channel, rules that out.

With a domain pointing at the host:

```bash
EECK_DOMAIN=chat.example.com EECK_HTTP_PORT=127.0.0.1:8080 \
  docker compose -f docker-compose.yml -f docker-compose.tls.yml up -d --build
```

Caddy fetches and renews a Let's Encrypt certificate automatically and becomes
the only public listener; binding `EECK_HTTP_PORT` to loopback keeps nginx off
the public interface.

### Who gets in, and who can end a chat

- **The first person in a chat is its host.** Everyone after that waits at the
  door until the host lets them in. While waiting, a joiner sees nothing of the
  conversation; the server lets only keys and a profile (name + avatar colour,
  end-to-end encrypted) pass between them and the host, so the host knows who
  is asking. If the host leaves, the longest-present member takes over.
- **Decisions are remembered in the host's browser** (`localStorage`, 48h), per
  key: someone let in once is let straight back in after a reconnect, someone
  declined stays declined. Nothing about this reaches the server.
- **Only the creator can delete a chat.** Creating a link returns an owner token
  that only the creating browser keeps; deleting requires it and disconnects
  everyone still in the room. The server stores only the token's hash.
- A name is a label anyone can choose. The id shown next to it is the key
  fingerprint, which can't be forged — compare that when it matters.

### Limits and logs

- Link creation is limited per client address (10/min), and so are open
  WebSockets (32 per address). Rooms cap at 64 connections, 16 of them waiting.
- `EECK_TRUST_PROXY=true` (set in `docker-compose.yml`) makes the server take
  the client address from `X-Forwarded-For`. Only set it when every request
  arrives through a proxy that overwrites that header — nginx here does. Without
  it the limits would see every client as nginx, or trust whatever a client sent.
- Chat ids and owner tokens are bearer secrets and are kept out of logs: the
  server redacts them from its call log, and nginx logs paths without query
  strings, with chat-link ids replaced. nginx's `error_log` may still quote a
  failing request line.

### Operational notes

- **Run one `server` replica.** Chat rooms are in-process actors and chat links
  live in an in-memory store, so a second replica would silently split rooms and
  lose links. Scaling out needs a shared store and a cross-process relay first.
- **State is deliberately not persisted.** Restarting `server` drops every live
  room and every issued link — that is the product behaviour ("no history"), not
  a deployment bug.

## Credits

This project reimplements the protocol and design of
[muke1908/chat-e2ee](https://github.com/muke1908/chat-e2ee) — see
[`SPEC.md`](./SPEC.md) for the reverse-engineered spec this codebase follows.
