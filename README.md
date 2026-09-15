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
