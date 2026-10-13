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

### Choosing a server from the client

Messages can't travel without a server, so the web client can be built to ask
for one first (`EECK_SERVER_MODE`, baked in at build time):

| Mode | First screen | How the browser reaches the server |
| --- | --- | --- |
| `same-origin` (default) | none | the server behind the same nginx |
| `relay` | server IPv4 address | this web host's nginx forwards `/s/<ip>:<port>/api\|ws` to it, so the server may be plain HTTP. Private/loopback addresses are refused |
| `direct` | server IP or host name | the browser connects itself over HTTPS (an IP uses `<ip-with-dashes>.sslip.io`, so the server needs Caddy/TLS). Used for the Vercel build (`web/vercel.json`) |

`EECK_SERVER_MODE=relay docker compose up -d --build` enables the relay. For
`direct`, list the web client's host in the server's `EECK_CORS_HOST`
(comma separated). The chosen server is stored in the shared link
(`?server=…&chatId=…`), so whoever opens it connects to the same server; the
address is checked against `/api/health` before continuing.

### Access, passwords and the admin panel

A server can require a **password** (`EECK_ACCESS_KEY`, or the one set in the admin panel). Without it
everything except `/api/health` is refused: no creating links, no joining chats, no WebSocket. People
type the password, **or a one-time code**, on the first screen; the server trades it for a session
token (`POST /api/auth/login`) and the client remembers only that token, never the secret. That is what
lets a one-time code be spent exactly once. Wrong guesses are limited to 10 a minute per address, so a
password is only as strong as it is long.

**One-time codes** are made in the admin panel: generated (10 characters, no look-alikes) or typed by the
admin (at least 8 characters, not a common one, not equal to the password). Each lives from a minute up
to a year (5 minutes, 15 minutes, 1 hour, 6 hours, 24 hours, 1 week, 1 month, 3 months, 1 year) and
works once. Only hashes are kept, in memory; a restart forgets them.

**The admin panel** is served by the chat server itself at `/admin` (server-rendered, no separate app to
host). It signs in with the **master key** and nothing else:

- Set it in **`.env`**: `EECK_MASTER_KEY=...`. If it is not set, the server generates a random key
  **for that run only and prints it in its log at start** (`Admin master key (EECK_MASTER_KEY is not
  set ...)`). That key lives in memory, is written nowhere else, and is different after every restart.
- The **Server access** tile is a dropdown: **Protected** (a password or one-time code is required) or
  **All accepted** (nobody is asked, the password and unspent codes are kept for when you switch back).
  The choice is saved in `auth.json` and wins over `EECK_ACCESS_KEY`; it applies to whoever connects next,
  people already connected are not dropped. Protected with no password set means only one-time codes get in.
- In the panel you can change the server password (kept across restarts, and then it replaces
  `EECK_ACCESS_KEY`), create and revoke one-time codes, and sign everybody out.
- Its session is an `HttpOnly`, `SameSite=Strict` cookie; every form carries an anti-forgery token;
  failed sign-ins are limited to 5 a minute per address. `EECK_ADMIN_ENABLED=false` removes it.
- Through the bundled nginx it is at `http://<host>/admin`; in relay mode the relay does not forward it,
  open the server's own address instead. Because it is on the public port, give it a long master key.

What the panel changes lives in `auth.json` in `EECK_DATA_DIR` (default `data/`; `/app/data` in Docker,
a named volume, **keep it or the changed password is lost with the container**). It holds the server
password as text, so the panel can show it, and the access mode; the master key is never stored. The file
only appears once you change one of them in the panel, and it should be readable by the server user only.
The **Server password** tile shows the password masked (`********`): clicking it fetches and shows it,
clicking again hides it, and it is never in the page source. A password set by an older version was
stored as a hash: it still works but cannot be shown until you change it once.

Settings come from the environment, and from a file named **`.env`** in the working directory
or the two folders above it (never `.env.example`); a real variable wins over the file. At startup the
server logs where it read them from and whether a password is required. Sessions and codes are in
memory, so run one server instance, and a restart signs people out.

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

## Deploy without Docker

For a host where Docker isn't the right fit — a plain Windows or Linux
machine — `build-release.bat` / `build-release.sh` build the server and the
web client and assemble a self-contained folder that runs as **one process**:
the server serves the built SPA itself (see `EECK_WEB_DIST`), so nothing
else — no nginx, no container — is needed to view the app.

```bat
build-release.bat            :: Windows
```
```bash
./build-release.sh           # Linux
```

`build-release.bat` produces **both** `release/eeck-windows/` and
`release/eeck-linux/` from one command: the server and web client only need
building once, only the OS-specific bits (Caddy binary, launch script) differ
per folder, and it uses WSL — if installed (`wsl --install`) — to set the
Linux folder's executable bits correctly, since plain Windows tools don't
reliably do that on NTFS. Without WSL it still produces the Windows folder,
with a note about what it skipped. `build-release.sh`, run on Linux, produces
only `release/eeck-linux/`.

Either folder is self-contained — copy it anywhere (another machine, no
build tools needed there) and run it. Each needs a Java 21 runtime on the
target machine (a JRE is enough); the Windows build prints where to get one
(Temurin) as a reminder when it finishes, and the Linux one prints the
`apt`/`dnf` package name.

```bat
release\eeck-windows\run.bat         :: plain HTTP, port 3001
```
```bash
release/eeck-linux/run.sh            # plain HTTP, port 3001
```

Fine for local use or an internal network. **Not for the open internet** —
see the HTTPS warning above; the same reasoning applies here.

### HTTPS, without Docker

The build also bundles [Caddy](https://caddyserver.com) (if `curl`/`tar` are
available — both ship with Windows and every mainstream Linux distro) so each
release can front itself with the same automatic-HTTPS setup as
`docker-compose.tls.yml`, just without Docker:

```bat
release\eeck-windows\run-https.bat chat.example.com
```
```bash
release/eeck-linux/run-https.sh chat.example.com
```

This binds the app to `127.0.0.1` only and puts Caddy in front of it on
ports 80/443, fetching and renewing a Let's Encrypt certificate for that
domain automatically. Needs, before running:

- that domain's DNS **A record already pointing at this machine's public IP**
  (Let's Encrypt has to reach the machine to verify it);
- ports **80 and 443 open inbound** (firewall, and your router/cloud
  security group if applicable) — both are needed even for an HTTPS-only
  site, since port 80 answers the ACME challenge and redirects to 443;
- permission to bind those ports: run as Administrator on Windows; on Linux,
  either run as root or grant the binary the capability once so nothing
  needs to run as root at all — `sudo setcap 'cap_net_bind_service=+ep'
  release/eeck-linux/caddy` (`run-https.sh` prints this if it fails without it).

For a real server, don't run these by hand in a terminal — install them as
systemd services instead, so they survive logout and restart on crash or
reboot. `release/eeck-linux/eeck.service.template` and
`eeck-caddy.service.template` (Linux only; both copied into the release
folder) have the exact steps in their header comments.



This project reimplements the protocol and design of
[muke1908/chat-e2ee](https://github.com/muke1908/chat-e2ee) — see
[`SPEC.md`](./SPEC.md) for the reverse-engineered spec this codebase follows.
