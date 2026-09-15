# chat-e2ee protocol — as implemented

Source: https://github.com/muke1908/chat-e2ee, commit `4f809adaeb16ddfad8d8edf4847b05f5611cc653` (2026-08-29), cloned read-only into `./reference`.

Files read in full: `app.ts`, `index.ts`, everything under `backend/`, everything under `service/`. All line numbers below refer to files under `./reference/` at the commit above.

This document describes only what the code does. Where the code's actual behavior is surprising, inconsistent with itself, or diverges from what a field/comment name implies, that is noted as an observation of current behavior, not a recommendation.

---

## 1. REST API

Mount points: `app.ts:21` mounts the API router at `/api`. `backend/api/index.ts:12-13` mounts `chatController` at `/api/chat` and `chatHashController` at `/api/chat-link`. CORS is wide open (`cors()` in `app.ts:12`, plus a manual `Access-Control-Allow-Origin: *` header in `app.ts:15-18`). `body-parser` limits JSON/urlencoded bodies to `64kb` (`app.ts:13-14`), though none of the endpoints below actually read a request body.

Every route is wrapped in `asyncHandler` (`backend/middleware/asyncHandler.ts:1-8`). Any thrown/rejected error inside a handler is caught there and turned into:

- Status `500`
- Body `{ error: string }`, where `error` is `e.message` unless `NODE_ENV === 'production'`, in which case it is the literal string `"Internal server error"` (`asyncHandler.ts:5`).

This is the implicit error path for every endpoint below in addition to the explicit status codes listed per-route.

### `GET /api`
`backend/api/index.ts:8-10`

- Request body: none.
- Response: `200`, `{ message: "/api is working!" }`.

### `POST /api/chat-link`
`backend/api/chatHash/index.ts:12-19`

Creates a new chat room/link record.

- Request body: none (no fields are read from `req`).
- Server logic: `generateHash()` (`backend/api/chatHash/utils/link.ts:19-32`) builds:
  ```
  { hash: string, expired: boolean, deleted: boolean }
  ```
  where `hash` is `uuidv4()` (`link.ts:20`), `expired` is always `false` (`link.ts:29`), `deleted` is always `false` (`link.ts:30`). This object is persisted via `db.insertInDb(link, LINK_COLLECTION)` (`LINK_COLLECTION = 'links'`, `backend/db/const.ts:1`).
- Response: `200`, body is the exact object above:
  ```ts
  { hash: string, expired: boolean, deleted: boolean }
  ```
- Note: if the env var `CHAT_LINK_DOMAIN` is unset, `link.ts:22-25` logs a `console.warn`, but `CHAT_LINK_DOMAIN` is never read anywhere else in the read sources and never appears in the response — it has no effect on the returned `hash` or on the invitation link, which is built entirely client-side (§4.1).

### `GET /api/chat-link/status/:channel`
`backend/api/chatHash/index.ts:20-35`

Checks whether a room id is currently usable.

- Path param: `channel` (string) — the `hash` from link creation.
- Server logic: `channelValid(channel)` (`backend/api/chatHash/utils/validateChannel.ts:17-39`) looks up `{ hash: channel }` in the `links` collection.
  - `CHANNEL_STATE` enum values (`validateChannel.ts:5-10`): `NOT_FOUND`, `ACTIVE`, `DELETED`, `EXPIRED`.
  - No record found → `{ valid: false, state: "NOT_FOUND" }`.
  - Record found: if `deleted === true` → state `"DELETED"`; else if `expired === true` → state `"EXPIRED"`; else → `{ valid: true, state: "ACTIVE" }`. (`deleted` is checked before `expired` — `validateChannel.ts:32`.)
  - Nothing in the read sources ever sets `expired: true` on a link record — `POST /api/chat-link` always creates `expired: false` (`link.ts:29`) and the only mutation to a link record anywhere (`PATCH`-style `updateOneFromDb`) is the delete route below setting `deleted: true`. `EXPIRED` is therefore a reachable enum value with no code path that produces it.
- Responses:
  - `200`, `{ status: "ok", state: "ACTIVE" }` when valid.
  - `410`, `{ error: "Channel deleted", state: "DELETED" }` when `state === "DELETED"` (`chatHash/index.ts:27-29`).
  - `404`, `{ error: "Invalid channel", state: "NOT_FOUND" | "EXPIRED" }` for any other invalid state (`chatHash/index.ts:30`).

### `DELETE /api/chat-link/:channel`
`backend/api/chatHash/index.ts:36-50`

Soft-deletes a room/link.

- Path param: `channel` (string).
- Server logic: `channelValid(channel)` is called and only `state` is used (`valid` is discarded — `chatHash/index.ts:40`). If `state` is `"DELETED"` or `"NOT_FOUND"` (`invalidstates`, `chatHash/index.ts:42`), the handler runs:
  ```ts
  return res.sendStatus(404).send("Invalid channel");
  ```
  (`chatHash/index.ts:44`). `res.sendStatus(404)` already sends a complete `404` response (status line + Express's default `"Not Found"` text body) and ends the response; the chained `.send("Invalid channel")` call executes against an already-sent response. As written, the client reliably observes a `404` status; the literal string `"Invalid channel"` is not a body Express can attach after `sendStatus()` has already flushed the response, and the chained call is a no-op/error condition in the running process (`asyncHandler`'s catch, if it fires from this line, itself calls `res.status(500).send(...)` on the same already-sent response). This is the code as it stands — there is no other status-code path for an invalid channel on delete.
  - This branch also fires for `EXPIRED` (a state not listed in `invalidstates`) falling through to the success path below, since `EXPIRED` is never actually produced (see previous section) and only `DELETED`/`NOT_FOUND` are excluded here.
- On any other state (i.e., `ACTIVE`, or the unreachable `EXPIRED`): `db.updateOneFromDb({ hash: channel }, { deleted: true }, LINK_COLLECTION)` (`chatHash/index.ts:47`) sets `deleted: true` on the matching record (an in-place merge, not a row removal — see §3).
- Responses:
  - `200`, `{ status: "ok" }` on success.
  - `404` (see caveat above) when the channel is already deleted or was never found.
- Request body: none.

### `GET /api/chat/get-users-in-channel`
`backend/api/messaging/index.ts:11-26`

Lists the users (by userID) currently bound to a channel's live sockets — **not** a query against the `links` DB collection; this reads server in-memory socket state (§3.2).

- Query param: `channel` (string), read as `req.query.channel` (`messaging/index.ts:14`).
- Server logic: `channelValid(channel)` gate first; if `!valid`, short-circuit. Then `clients.getClientsByChannel(channel)` (`backend/socket.io/clients.ts:30-35`) returns the in-memory `{ [userID]: { sid } }` map for that channel (or `{}`), mapped to `Object.keys(...).map(userId => ({ uuid: userId }))` (`messaging/index.ts:22-23`).
- Response type, `UsersInChannelResponse` (`backend/api/messaging/types.ts:2`):
  ```ts
  { uuid: string }[]
  ```
  Each element's `uuid` is the socket-bound `userID` string a client supplied at `chat-join` — not a server-generated identifier.
- Responses:
  - `200`, `{ uuid: string }[]` (possibly `[]` if the channel is valid but nobody is currently connected).
  - `404` via bare `res.sendStatus(404)` (no body) when `channelValid()` reports `!valid` (`messaging/index.ts:19`) — this is a single unchained call, not subject to the double-send issue above.

---

## 2. Socket.IO events

Socket.IO server setup: `backend/socket.io/index.ts:41-61`. `maxHttpBufferSize: 64 * 1024` (64 KiB, `index.ts:39,48`) bounds any single packet at the transport level; CORS origin is `"*"` with `credentials: true` (`index.ts:49-52`); `allowEIO3: true` allows old Engine.IO v3 clients. All application listeners are attached per-connection in `connectionListener` (`backend/socket.io/listeners.ts:39-158`), called from `io.on("connection", ...)` (`index.ts:58`).

`socketEmit()` (`index.ts:63-70`) is the only way the server sends events; it looks up a live socket by `sid` via `io.sockets.sockets.get(sid)` and calls `.emit()` directly on it — i.e., every server→client event below targets one specific socket, never a room broadcast.

Every payload the server relays for chat/signaling is an opaque `WireEnvelope` (`index.ts:10`):
```ts
{ version: number, strategy: string, data: unknown }
```
The server never inspects, decrypts, or validates the contents of `data` — see `index.ts:9` comment and §4.5.

`findPeerSid(socket)` (`listeners.ts:31-37`) is how the server resolves "the other participant": it looks up the receiver's `userID` via `clients.getReceiverIDBySenderID(socket.userID, socket.channelID)`, then that user's live socket id via `clients.getSIDByIDs(...)`. It uses only `socket.userID`/`socket.channelID` — values bound to the socket at `chat-join` time, never any client-supplied `sender`/`channel` field in later events.

A shared rate limiter instance (`rateLimiter`, `listeners.ts:11`) is a token bucket per socket id: capacity 40, refill 10 tokens/sec (`RateLimiter`, `backend/socket.io/rateLimiter.ts:22-51`); it is consumed by `chat-message` and `webrtc-signal` and reset on `disconnect` (`listeners.ts:139`). A size check `isPayloadTooLarge()` (`listeners.ts:16-22`) rejects any `chat-message`/`webrtc-signal` payload whose JSON-stringified byte length exceeds `MAX_ENVELOPE_BYTES = 32 * 1024` (32 KiB, `listeners.ts:9`).

### 2.1 Client → server events

| event | payload | who emits | server precondition(s) | ack | server-side effect |
|---|---|---|---|---|---|
| `chat-join` | `{ userID: string, channelID: string }` | any connected socket, once per session (`service/src/sdk.ts:178`, via `SocketInstance.joinChat`, `service/src/socket/socket.ts:67-70`) | — | none | See below |
| `chat-message` | `{ envelope: WireEnvelope }` | a joined socket (`socket.ts:73-75`) | joined; rate limit; size ≤32KB; a peer is present | `{ id, timestamp }` or `{ error }` | Relays `chat-message` to the peer socket |
| `webrtc-signal` | `{ envelope: WireEnvelope }` | a joined socket (`socket.ts:78-80`) | joined; rate limit; size ≤32KB; a peer is present | `{ status: "ok" }` or `{ error }` | Relays `webrtc-session-description` to the peer socket |
| `received` | `{ id: string \| number }` | a joined socket, automatically on every inbound `chat-message` (`socket.ts:56-59, 106-109`) | — (no-op if no peer) | none | Relays `delivered` (bare `id`) to the peer socket |
| `disconnect` (built-in Socket.IO event) | — | Socket.IO itself, on transport close | — | — | See below |

**`chat-join`** (`listeners.ts:40-73`):
1. If `userID` or `channelID` is missing/falsy: `console.error(...)`; return. No error is sent to the client — the join simply fails silently from the client's perspective.
2. `channelValid(channelID)`; if invalid: `console.error(...)`; return (again, silent to the client).
3. Count existing users in `clients.getClientsByChannel(channelID)`. If `userCount === 2`: emit `limit-reached` (payload `null`) to the *joining* socket, then `socket.disconnect()` (`listeners.ts:56-58`) — the joining socket is forcibly dropped, never registered.
4. Otherwise: `clients.setClientToChannel(userID, channelID, socket.id)` registers the socket, and `socket.channelID`/`socket.userID` are set on the socket instance (`listeners.ts:61-63`) — these two fields are what every later event on this socket authenticates against.
5. If another participant is already present in the channel, emit `on-alice-join` (payload `null`) to *that* peer's socket (`listeners.ts:68-72`). No key material is included — see §4.

There is no explicit uniqueness check on `userID` within a channel beyond the 2-participant cap; if the same `userID` calls `chat-join` twice for the same channel, the second call overwrites the first's `sid` entry in `clientRecord[channelID][userID]` (`clients.ts:59-67`) without incrementing `userCount` past what a single distinct-userID slot represents.

**`chat-message`** (`listeners.ts:75-103`):
- Rejections (via `ack({ error })`, no relay, no side effect beyond the check itself):
  - `"Join a channel before sending messages."` if `!socket.userID || !socket.channelID`.
  - `"Rate limit exceeded."` if `rateLimiter.consume(socket.id)` returns `false`.
  - `"Payload too large."` if `isPayloadTooLarge(payload)`.
  - `"No receiver is in the channel."` if `findPeerSid(socket)` returns nothing.
- Success: `id = timestamp = Date.now()` (the same numeric value used for both fields — `listeners.ts:94-95`); server emits `chat-message` to the peer with `{ id, timestamp, sender: socket.userID, envelope: payload?.envelope }`; acks the sender with `{ id, timestamp }`.

**`webrtc-signal`** (`listeners.ts:105-128`): identical precondition/rejection structure to `chat-message` (same four error strings, same rate limiter and size cap). Success: emits `webrtc-session-description` to the peer with `{ envelope: payload?.envelope }`; acks the sender with `{ status: "ok" }`.

**`received`** (`listeners.ts:130-135`): looks up the peer via `findPeerSid`; if found, emits `delivered` to the peer with the bare `id` value from the payload (not wrapped in an object) — `socketEmit(SOCKET_TOPIC.DELIVERED, receiverSid, id)`. No ack, no error surfaced if there is no peer.

**`disconnect`** (`listeners.ts:137-153`):
1. `rateLimiter.reset(socket.id)` — always, even if the socket never joined a channel.
2. If `channelID`/`userID` were never set on the socket (never joined), return.
3. Otherwise: resolve peer via `findPeerSid` *before* removing this client; `clients.deleteClient(userID, channelID)` removes only this `userID`'s entry from `clientRecord[channelID]` (`clients.ts:69-71}` — the `channelID` key itself is left behind, now possibly pointing at an empty object). If a peer was found, emit `on-alice-disconnect` (payload `null`) to them.
4. Wrapped in try/catch; a caught error is only `console.log`ged (`listeners.ts:149-151`).

### 2.2 Server → client events

| event | payload | server sends when | recipient |
|---|---|---|---|
| `message` | `"ping!"` (bare string) | unconditionally, once, as the very last line of `connectionListener` (`listeners.ts:155`) — i.e. immediately on every new socket connection, before any `chat-join` | the newly connected socket itself |
| `limit-reached` | `null` | a third distinct `chat-join` attempt on a channel that already has 2 participants (`listeners.ts:55-56`) | the socket that just attempted to join (which is then disconnected) |
| `on-alice-join` | `null` | a second participant successfully joins a channel that already has one (`listeners.ts:71`) | the first (already-present) participant's socket |
| `chat-message` | `{ id: number, timestamp: number, sender: string, envelope: WireEnvelope }` | a valid `chat-message` was relayed (`listeners.ts:96-101`) | the sender's channel peer |
| `webrtc-session-description` | `{ envelope: WireEnvelope }` | a valid `webrtc-signal` was relayed (`listeners.ts:124-126`) | the sender's channel peer |
| `delivered` | `id: string \| number` (bare value) | the peer emitted `received` for a message this socket sent (`listeners.ts:133`) | the original message sender |
| `on-alice-disconnect` | `null` | a joined socket disconnects while a peer is present (`listeners.ts:147`) | the remaining peer's socket |

`SOCKET_TOPIC` enum (server-side name → wire event string), `index.ts:13-21`: `CHAT_MESSAGE='chat-message'`, `LIMIT_REACHED='limit-reached'`, `ON_ALICE_JOIN='on-alice-join'`, `DELIVERED='delivered'`, `ON_ALICE_DISCONNECTED='on-alice-disconnect'`, `MESSAGE='message'`, `WEBRTC_SESSION_DESCRIPTION='webrtc-session-description'`.

Client-side wire mapping (`service/src/socket/socket.ts:30-37`, `WIRE_EVENTS`) registers listeners for `limit-reached`, `delivered`, `on-alice-join`, `on-alice-disconnect`, `chat-message`, `webrtc-session-description` (`socket.ts:52-62`) — it does **not** register any listener for the `message`/`"ping!"` event; the client SDK receives it but has no handler, so it is a no-op from the SDK's perspective. `SocketListenerType` (the set of events an SDK consumer may subscribe to via `chat.on(...)`, `socket.ts:8`) is `"limit-reached" | "delivered" | "on-alice-join" | "on-alice-disconnect" | "chat-message"` — note `webrtc-session-description` is deliberately not in this public list; it's consumed internally by the SDK (`sdk.ts:515-520`) to drive call/signaling logic and is never re-exposed raw to a consumer.

On receipt of `chat-message`, the client SDK immediately (synchronously, before any decryption/validation) emits `received {id: msg.id}` back to the server (`socket.ts:56-59, 106-109`) — delivery is acknowledged at the transport layer regardless of whether the envelope later fails to decrypt or is dropped as a replay (§4.5).

---

## 3. Session data model

### 3.1 Link/channel records (persisted)

Type `LinkType` (`backend/api/chatHash/utils/link.ts:5-9`):
```ts
{ hash: string, expired: boolean, deleted: boolean }
```
- `hash`: a `uuidv4()` string; this is the public room id, sent to and returned by the server, and the only channel identifier ever transmitted over the network (REST or socket).
- `expired`: always `false` at creation; never programmatically set `true` anywhere in the read sources (see §1, `GET /api/chat-link/status/:channel`).
- `deleted`: `false` at creation; set to `true` in place by `DELETE /api/chat-link/:channel` (`chatHash/index.ts:47`). This is a soft delete — `db.updateOneFromDb` merges `{ deleted: true }` into the existing record; nothing in the read sources ever removes a link record from storage entirely. A deleted link's document persists indefinitely (subject to whatever the underlying MongoDB deployment's own retention is — no TTL index or expiry job is created in this codebase).

Collection name: `LINK_COLLECTION = 'links'` (`backend/db/const.ts:1`).

**No secret or key material of any kind is ever stored server-side.** The link record's only fields are `hash`/`expired`/`deleted`. The invitation secret and everything derived from it exist exclusively on each client device (§3.3).

Storage backend (`backend/db/index.ts`):
- If `process.env.MONGO_URI` is unset, the module-level `inMem` flag starts `true` (`db/index.ts:11`) and every `insertInDb`/`findOneFromDB`/`updateOneFromDb` call is routed to the in-memory implementation (`backend/db/inMemDB.ts`) for the lifetime of the process.
- If `MONGO_URI` is set, `inMem` starts `false`, and `connectDb()` (called from `index.ts:11` inside the root `index.ts`'s `app.listen()` callback, i.e. after the HTTP server has already started accepting connections) attempts a real MongoDB connection. On any error (missing URI, connect failure, etc.) it logs the error and sets `inMem = true` permanently for the rest of the process (`db/index.ts:26-31`) — there is no retry.
- Because `connectDb()` runs asynchronously and is not awaited by anything that gates request handling, a request that reaches `insertInDb`/`findOneFromDB`/`updateOneFromDb` after the server starts listening but before `connectDb()` has resolved will see `inMem === false` (if `MONGO_URI` was set) while the module-level `db` variable (`db/index.ts:10`) is still `null`, so `db.collection(...)` is called on `null`.
- In-memory implementation (`inMemDB.ts`): a single process-global plain object `storage` (`inMemDB.ts:11`) keyed by collection name → array of records. `insertInDb` pushes `{ pk, ...data }` (`inMemDB.ts:19-23`, `pk` is a module-global auto-incrementing counter, never reset, never exposed in any API response). `findOneFromDB`/`updateOneFromDb` do a linear scan matching every key in the query object exactly (`findOneFromArr`, `inMemDB.ts:2-9`). Entirely lost on process restart.

### 3.2 Client/presence record (in-memory only, never persisted)

`Clients` class (`backend/socket.io/clients.ts:25-79`), a single process-wide singleton (`getClientInstance()`, `clients.ts:81-84`). Internal shape (`clients.ts:1-16`):
```ts
{ [channelID: string]: { [userID: string]: { sid: string } } }
```
- Populated by `setClientToChannel(userID, channelID, sid)` on `chat-join` (`clients.ts:59-67`) — overwrites any prior entry for that exact `userID`/`channelID` pair.
- Read by `getClientsByChannel`, `getReceiverIDBySenderID`, `getSIDByIDs` (used throughout `listeners.ts` to resolve "the other participant" and by `GET /api/chat/get-users-in-channel`).
- Removed by `deleteClient(userID, channelID)` on socket `disconnect` (`clients.ts:69-71`) — deletes only the one `userID` key; the `channelID` key itself is never cleaned up even once its last user leaves, so `clientRecord` grows one stale empty-object entry per channel that is ever joined and then fully vacated, for the lifetime of the process.
- This record is entirely independent of the `links` DB collection: a link can exist in the DB with zero connected clients (nobody has joined yet, or everyone disconnected), and a `channelID` can only ever appear here if `channelValid()` passed at `chat-join` time — there is no code path that adds a client entry for a channel that failed validation.

### 3.3 Client-side key material (never sent to, or stored by, the server)

All of the following exist only in the browser process of each participant, never transmitted over REST or the socket, and never persisted server-side:

- **Invitation secret**: 256 bits from `window.crypto.getRandomValues` (`generateInviteSecret`, `service/src/crypto/inviteCrypto.ts:38-41`), base64url-encoded. Generated once, by whichever client calls `getLink()` (`service/src/api/links.ts:26-33`), immediately after the server returns the room `hash`. Carried only in the invitation link's URL fragment `#room=<hash>&secret=<secret>` (`links.ts:13-19`), which browsers never transmit as part of an HTTP request.
- **Derived channel secrets**: `deriveChannelSecrets(secret)` (`inviteCrypto.ts:52-71`) runs HKDF-SHA256 (via `window.crypto.subtle`) twice against the same invitation `secret`, with a fixed salt `"chat-e2ee:v1"` (`inviteCrypto.ts:27`) and two distinct `info` labels — `"chat-e2ee:chat:v1"` and `"chat-e2ee:signaling:v1"` (`inviteCrypto.ts:28-29`) — producing `chatSecret` and `signalingSecret`, each 256 bits, base64url-encoded. The room id is never folded into this derivation (`inviteCrypto.ts:18-19` comment) — it remains pure routing state known to the server, with no cryptographic role. `deriveChannelSecrets` throws if the decoded `secret` is under 256 bits (`inviteCrypto.ts:54-56`).
- **`CryptoKey` objects**: `chatSecret`/`signalingSecret` are each handed to their own `EncryptionStrategy` instance's `initialize()` (default: `secureStrategy.ts`, AES-256-GCM — `window.crypto.subtle.importKey('raw', ..., 'AES-GCM', false, ['encrypt','decrypt'])`, `secureStrategy.ts:49`). The two strategy instances (`ChatE2EE.chatStrategy`/`.signalingStrategy`, `sdk.ts:56-57`) never share in-memory state with each other. `CryptoKey` values never leave `secureStrategy.ts` (module-private closure variable `key`, `secureStrategy.ts:37`).
- Torn down by `destroy()` on each strategy (`sdk.ts:552-565`, best-effort — a throwing `destroy()` on one strategy does not prevent the other's teardown or clearing of local instance fields), called from `ChatE2EE.dispose()` and `ChatE2EE.delete()`.

---

## 4. Lifecycle: link creation → first join → second join → key exchange → message exchange → leave → link deletion

### 4.1 Link creation
- Client calls `chat.getLink()` (`sdk.ts:145-148`) → `getLink()` (`service/src/api/links.ts:26-33`) → `POST /api/chat-link`.
- Server generates `{ hash, expired: false, deleted: false }` (`link.ts:19-32`), persists it to the `links` collection (`chatHash/index.ts:15-17`), returns it.
- Client generates a fresh 256-bit `secret` locally (`generateInviteSecret`, `inviteCrypto.ts:38-41`) and builds `{ link, absoluteLink }` from `window.location` plus the fragment `#room=<hash>&secret=<secret>` (`links.ts:13-19`).
- Returned `LinkObjType` (`service/src/public/types.ts:14-21`): `{ hash, secret, link, absoluteLink, expired, deleted }`. `secret` at this point exists only in this client's memory/return value; it has not yet been shared with anyone.

### 4.2 First user joins
- The creator (or whoever received the link first) calls `chat.setChannel(roomId, secret, userId, userName?)` (`sdk.ts:156-180`).
- `deriveChannelSecrets(secret)` runs; both `chatStrategy`/`signalingStrategy` are `initialize()`d; `channelReady = true`; local `chatSeq` is reset to `0` and `chatReplayGuard.clear()`ed (`sdk.ts:172-177`) so a fresh room always starts a fresh sequence-number space.
- `socket.joinChat({ userID, channelID: roomId })` emits `chat-join` (`socket.ts:67-70`).
- Server: `channelValid(channelID)` passes (record exists, not deleted/expired); `userCount` in that channel is `0`; `clients.setClientToChannel(...)` registers this socket; `socket.userID`/`socket.channelID` are bound (`listeners.ts:61-63`). No peer exists yet, so no `on-alice-join` is emitted to anyone.

### 4.3 Second user joins
- The second participant obtains the same `roomId`+`secret` out of band (via the shared link) and calls `setChannel(roomId, secret, otherUserId)` — identical client-side flow to §4.2.
- Server: `channelValid` passes; `userCount` is now `1`; this socket is registered as the second entry under `clientRecord[channelID]`. Because a peer is now present, `on-alice-join` (payload `null`) is emitted to the *first* participant's socket (`listeners.ts:68-72`).
- If a *third* distinct `userID` attempts `chat-join` against the same channel while two are already registered, the server emits `limit-reached` to that joining socket and disconnects it (`listeners.ts:55-59`) — it is never registered, and no second `on-alice-join` is ever sent for it.

### 4.4 Key exchange
There is no runtime key-exchange message on the wire at all — no public keys, no handshake payload, nothing sent via REST or socket carries cryptographic material. `on-alice-join`'s payload type is `null` (`SOCKET_TOPIC` typing, `index.ts:32`) and its comment (`listeners.ts:65-67`) states no key material is exchanged there or anywhere else. Instead:
- Both participants independently ran `deriveChannelSecrets(secret)` (§3.3) against the *same* invitation `secret`, which they obtained purely out-of-band via the shared link fragment.
- Since the salt, both `info` labels, and the input `secret` are identical on both sides, both clients independently arrive at the identical `chatSecret`/`signalingSecret` pair without either value (or the invitation `secret` itself) ever touching the server.
- `on-alice-join` therefore functions purely as a presence signal — "the other side is now connected and can receive relayed envelopes" — not as a trigger for any key derivation or exchange step.

### 4.5 Message exchange
- Sender: `chat.encrypt({ text, image }).send()` (`sdk.ts:207-226`):
  1. `seq = ++this.chatSeq` (monotonically increasing per `ChatE2EE` instance, per room).
  2. Builds `ChatPlaintext = { seq, timestamp: Date.now(), text, image }` (`sdk.ts:35`).
  3. JSON-encodes it to bytes (`encodePayload`, `sdk.ts:38`) and calls `chatStrategy.encrypt(bytes)`. Default strategy (`secureStrategy.ts:52-65`): random 12-byte IV, AES-256-GCM with additional authenticated data `"chat-e2ee:aes-256-gcm:v1"` (`buildAad`, `secureStrategy.ts:27`), producing envelope `{ version: 1, strategy: "aes-256-gcm", data: { iv, ct } }` (both base64url).
  4. `socket.sendChatMessage(envelope)` emits `chat-message {envelope}` with an ack Promise (`socket.ts:73-75`).
- Server relays to the peer as described in §2.1/§2.2, assigning `id = timestamp = Date.now()`, and acks the sender with `{ id, timestamp }` — `sdk.ts:222-223` converts both to `String(...)` for its own return value `ISendMessageReturn { id: string, timestamp: string }` (`public/types.ts:23`).
- Receiver: on the raw `chat-message` socket event, `SocketInstance` (`socket.ts:56-59`) (a) hands the still-encrypted message to `onRawChatMessage` and (b) immediately emits `received {id}` back to the server (transport-level delivery ack, independent of whether decryption later succeeds).
- `ChatE2EE.handleRawChatMessage` (`sdk.ts:327-342`):
  1. `assertChannelReady()` — throws if `setChannel()` hasn't completed.
  2. `assertEnvelopeMatchesStrategy(envelope, chatStrategy)` (`sdk.ts:543-549`) — throws if `envelope.strategy !== chatStrategy.id` (rejects foreign/mismatched-strategy envelopes before ever calling `decrypt()`).
  3. `chatStrategy.decrypt(envelope)` — throws on wrong version/malformed shape/failed AEAD auth tag (`secureStrategy.ts:67-93`).
  4. `chatReplayGuard.accept('chat', payload.seq)` (`ReplayGuard`, `service/src/utils/replayGuard.ts:19-26`) — `false` (message dropped, logged, nothing fired) if `payload.seq` is not strictly greater than the last accepted `seq` for the `'chat'` context on this instance.
  5. On success, fans out to `chat-message` subscribers with `{ sender: msg.sender, message: payload.text, image: payload.image, id: msg.id, timestamp: msg.timestamp }` — note `id`/`timestamp` delivered to the consumer are the server-assigned transport values from the outer socket message, not from the encrypted `payload`.
- Any failure at steps 1–3, or a replay/duplicate at step 4, drops the message outright: `sdk.ts:511-513` catches the rejected promise from `handleRawChatMessage` and only logs it — there is no plaintext fallback and no partial delivery, and the transport-level `received`/`delivered` ack has already fired regardless (it happens before decryption is attempted).

### 4.6 Leave
- A participant calls `chat.dispose()` (`sdk.ts:250-257`): calls `socket.dispose()` → `this.socket.disconnect()` (`socket.ts:82-85`), clears the `subscriptions` map, calls `clearChannelSecrets()` (destroys both strategies' key material, resets `roomId`/`userId`/sequence counters/replay guard — `sdk.ts:552-573`), and sets `initialized = false`.
- There is no explicit "leave"/"chat-leave" socket event anywhere in the read sources — leaving is entirely implicit in the underlying Socket.IO `disconnect`.
- Server's `disconnect` handler (§2.1) resets that socket's rate-limit bucket, removes its `userID` entry from the in-memory `clientRecord[channelID]` (leaving the now-possibly-empty `channelID` key behind — §3.2), and if a peer remains connected, emits `on-alice-disconnect` to them.

### 4.7 Link deletion
- Either participant may call `chat.delete()` (`sdk.ts:194-199`): `deleteLink({ channelID: this.roomId })` (`service/src/api/links.ts:36-40`) → `DELETE /api/chat-link/:channel`, then unconditionally calls `clearChannelSecrets()` locally regardless of the server's response (the `await` is on the request itself; any REST error propagates as a rejected promise from `delete()`, but if it resolves, secrets are cleared right after).
- Server soft-deletes as described in §1 (`deleted: true` merged into the existing record) — the record is never removed from storage.
- **Deletion does not disconnect any connected sockets and does not touch the in-memory client/presence record (§3.2) at all.** The only server-side check against `deleted`/`expired` is `channelValid()`, and the only place that is called is (a) `GET/DELETE /api/chat-link*`, (b) `GET /api/chat/get-users-in-channel`, and (c) `chat-join`. None of `chat-message`, `webrtc-signal`, or `received` re-validate the channel's DB state. Consequently: two sockets already joined to a channel before it is deleted can continue exchanging `chat-message`/`webrtc-signal` freely and indefinitely after the link is marked `deleted: true` — deletion only prevents a *new* `chat-join` (or a status check, or a fresh `getUsersInChannel` call) from succeeding against that `hash` going forward.

---

## 5. WebRTC call protocol

All call signaling (control messages, SDP offer/answer, ICE candidates) is sealed through the same `signalingStrategy` instance used for nothing else, and relayed exclusively via the `webrtc-signal`/`webrtc-session-description` socket events described in §2 — there is no separate REST or socket surface for calls. Media itself relies solely on WebRTC's own mandatory DTLS-SRTP transport encryption; there is no additional per-frame/encoded-transform encryption layered on top (`webrtcCall.ts:26-30`, `peer.ts:53-55`), and `WebRTCCall.isSupported()` is a bare `typeof RTCPeerConnection !== 'undefined'` check (`webrtcCall.ts:31-33`).

### 5.1 Signal payload shapes
`WebRtcSignalPayload` union (`service/src/webrtc/types.ts:75-79`), each carrying `SignalMetadata { callId: string, seq: number, timestamp: number }` (`types.ts:45-49`):
- `OfferSignalData`: `{ type: 'offer', sdp: string, callId, seq, timestamp }`.
- `AnswerSignalData`: `{ type: 'answer', sdp: string, callId, seq, timestamp }`.
- `IceCandidateSignalWithMetadata`: `{ type: 'candidate', candidate: RTCIceCandidateInit, callId, seq, timestamp }`.
- `CallControlSignal`: `{ type: 'call-invite'|'call-accept'|'call-reject'|'call-cancel'|'call-end'|'call-timeout', callId, seq, timestamp, reason?: CallEndReason }` (`types.ts:63-66`).

`CallEndReason` (`types.ts:20-26`): `'local-end' | 'remote-end' | 'rejected' | 'cancelled' | 'timeout' | 'failed'`.

`this.signalSeq` on `ChatE2EE` (`sdk.ts:69`) is a single monotonic counter shared across every control signal and every offer/answer/ICE-candidate signal sent by that instance (incremented in `sendControlSignal`, `sdk.ts:434`, and via the `signalMetadataProvider` closure passed into each `WebRTCCall`, `sdk.ts:580-585`) — it is reset to `0` whenever a fresh outgoing call starts (`startCall`, `sdk.ts:272`), **not** reset by `setChannel()`.

### 5.2 Two independent staleness checks on receipt
Incoming signals pass through two separate, independently-tracked seq/freshness checks:
1. `ChatE2EE.isStaleSignal()` (`sdk.ts:497-505`), keyed by `callId` in `this.lastSignalSeqByCall` — applied to every decrypted signaling payload in `handleCallSignal()` (`sdk.ts:357-360`), i.e. control signals *and* anything later delegated to the router.
2. `CallSignalRouter.isStale()` (`webrtcCall.ts:212-219`), keyed by `callId` in its own `lastSeqByCall` — applied a second time, only to `offer`/`answer`/`candidate` signals once `handleSignal()` runs (`webrtcCall.ts:130-133`); control-type (`'call-*'`) signals are explicitly skipped here (`isControlSignal`, `webrtcCall.ts:134-136, 208-210`) since `handleCallSignal` already consumed them upstream and never forwards control signals into the router.

Both checks drop (log and return) any signal whose `seq` is `<=` the last one seen for that `callId`, with no exception for the first signal of a new `callId` (absent entries pass by definition, `typeof last === 'number'` guard).

### 5.3 Call setup — caller side
`startCall()` (`sdk.ts:259-281`):
1. `WebRTCCall.isSupported()` check; throws `'WebRTC is not supported in this environment.'` if not.
2. Throws `'Call already active'` if `callSignalRouter.activeCall` is already set.
3. `assertCallPreconditions()` (`sdk.ts:418-425`): calls `getUsersInChannel()` (REST, §1) and throws `'No user available to accept call'` (after setting lifecycle `'no-peer'`) unless at least 2 users are currently listed — note this is a one-time REST snapshot check at call-start, not a live subscription; it does not re-check on `on-alice-disconnect` during ringing.
4. New `activeCallId = generateUUID()` (client-side UUID v4-shaped generator, `service/src/utils/uuid.ts` — uses `Math.random()`, not `crypto.getRandomValues`); `signalSeq` reset to `0`.
5. `createWebRtcCall(activeCallId)` constructs a `WebRTCCall`→`Peer` pair (acquiring a local audio-only `getUserMedia` track immediately, `peer.ts:146-154`); `callSignalRouter.attachCall(...)`.
6. Lifecycle → `'initiating'`; sends `call-invite` control signal (`sendControlSignal`, `sdk.ts:427-439`, `seq` becomes `1`); lifecycle → `'ringing'`; schedules a 30-second outgoing-invite timeout (`scheduleOutgoingInviteTimeout`, `sdk.ts:448-462`).
7. If still `'ringing'` when the timer fires: sends a `call-timeout` control signal, fires `call-timeout` subscribers, and ends the call locally with reason `'timeout'`.

### 5.4 Call setup — callee side
- Arrival of the `call-invite` **control signal** (decrypted, via `handleCallSignal`, `sdk.ts:361-369`): if there's already an active call or a pending offer, the new invite is auto-rejected (sends `call-reject` with reason `'rejected'`) — a client can only ever be presented with one incoming call at a time. Otherwise: `activeCallId = data.callId`; lifecycle → `'incoming'`; fires `call-invite` subscribers with `{ callId }`.
- Independently, the actual SDP **`offer`** signal (not a control signal) arrives and is routed by `CallSignalRouter.handleSignal()` (`webrtcCall.ts:138-149`): if there's no active/matching call and it wasn't already explicitly accepted, it is buffered as `pendingIncomingOffer` and `onIncomingOffer(callId)` fires — which, in `sdk.ts:85-91`, sets `activeCallId` and (guarded by `if (this.callLifecycleState !== 'incoming')`) also transitions to `'incoming'` and fires `call-invite` subscribers a second, independent time. Both the control-signal path and the offer-arrival path can therefore each independently produce a `call-invite` event/`'incoming'` transition for the same call, guarded only by the lifecycle-state check in the second path.
- `acceptCall()` (`sdk.ts:283-293`): requires `activeCallId` to already be set (from either path above); sends `call-accept` control signal; calls `callSignalRouter.acceptPendingOffer(activeCallId)` (`webrtcCall.ts:164-179`), which creates the real `WebRTCCall`, immediately forwards the buffered offer into it (triggering `Peer.signal()`'s offer branch: `setRemoteDescription` → `createAnswer` → `setLocalDescription` → send `answer` signal, `peer.ts:109-122`), and flushes any ICE candidates buffered under that `callId`. Fires `call-added` subscribers with the new `activeCall`. Lifecycle → `'connecting'`.
- Separately, on receiving the peer's `call-accept` control signal (caller side, `sdk.ts:373-381`): clears the outgoing-invite timeout, lifecycle → `'connecting'`, and calls `callSignalRouter.activeCall?.startCall()` — this is what actually creates and sends the SDP **offer** (`Peer.createAndSendOffer`, `peer.ts:94-106`) on the caller's side. In other words, the ringing/accept handshake (`call-invite`/`call-accept` control signals) is fully decoupled from, and precedes, the actual SDP offer/answer exchange: the caller only sends its real `offer` after receiving `call-accept`.

### 5.5 Call teardown
- `rejectCall()` (`sdk.ts:295-304`): only meaningful if there is a `pendingCallId`; sends `call-reject` (reason `'rejected'`), clears buffered ICE candidates for that call (`rejectPendingOffer`, `webrtcCall.ts:181-189`), ends the call locally with terminal state `'rejected'`.
- `cancelCall()` (`sdk.ts:306-312`): sends `call-cancel` (reason `'cancelled'`), ends locally with terminal state `'cancelled'`.
- `endCall(reason = 'local-end')` (`sdk.ts:314-319`): if `activeCallId` is set, sends `call-end` with that reason; always ends locally with terminal state `'ended'` (the default).
- Receiving the peer's `call-reject`/`call-cancel`/`call-timeout`/`call-end` control signal (`sdk.ts:383-413`), each gated on `data.callId === this.activeCallId`: fires the matching `call-rejected`/`call-cancelled`/`call-timeout`/`call-ended` subscriber event (the last also carries `reason`), then ends locally with reason `'rejected'`/`'cancelled'`/`'timeout'`/`'remote-end'` respectively.
- `endLocalCall(reason, terminalState = 'ended')` (`sdk.ts:481-495`): clears the outgoing-invite timer; lifecycle → `'ending'`; calls `activeCall?.endCall()` (stops local audio tracks, detaches the audio sink, closes the `RTCPeerConnection` — `peer.ts:133-144`); resets the `CallSignalRouter` (clears active/pending call refs and buffered ICE candidates — `webrtcCall.ts:192-199`); fires `call-removed` subscribers (no payload); if `terminalState !== 'ended'`, an *additional* lifecycle transition to that specific `terminalState` (e.g. `'rejected'`) fires first; `activeCallId` is cleared; then a final lifecycle transition to `'ended'` fires. So a peer-initiated rejection, for example, produces three separate `call-state-changed` events in order: `'ending'` → `'rejected'` → `'ended'`.
- Peer-connection state changes also drive teardown automatically (`setupCallSubs`, `sdk.ts:95-112`, subscribing to `Peer`'s `onconnectionstatechange` via `WebRTCCall`'s `'state-changed'` event, `peer.ts:58-64`): `'connecting'`→lifecycle `'connecting'`; `'connected'`→clears the outgoing timeout, lifecycle `'connected'`; `'failed'`→`endCall('failed')`; `'closed'`→`endCall('remote-end')`; `'disconnected'`→lifecycle `'ice-failed'` only (does **not** call `endCall()` — a merely-disconnected (not failed/closed) peer connection is not automatically torn down).

### 5.6 ICE / media specifics
- ICE servers: 10 hard-coded public Google STUN endpoints (`DEFAULT_ICE_SERVERS`, `peer.ts:14-25`). No TURN server is configured anywhere in the read sources.
- Each gathered local ICE candidate is sent as its own `candidate` signal immediately as it's discovered (`pc.onicecandidate`, `peer.ts:66-77`), tagged with the same `resolveSignalMetadata()`-provided `callId`/incrementing `seq`/`timestamp` as offers/answers.
- Incoming `candidate` signals are forwarded to the active call if its `callId` matches; otherwise buffered in `CallSignalRouter.bufferedIceCandidates` until a matching call is attached (`attachCall`) or created (`acceptPendingOffer`), at which point they are flushed in original order for that `callId` (`flushBufferedIceCandidates`, `webrtcCall.ts:201-206`).
- Only an audio track is ever added to the peer connection (`getUserMedia({ audio: true, video: false })`, `peer.ts:152-154`); `ontrack` only handles `getAudioTracks()` (`peer.ts:79-84`) and attaches the remote stream to a dynamically created `<audio autoplay>` element appended to `document.body` (`AudioSink.attach`, `service/src/webrtc/audioSink.ts:14-32`) — if `.play()` is rejected (e.g. autoplay policy), it adds `controls` and retries once after a 1-second `setTimeout` (`audioSink.ts:21-29`).

### 5.7 Consumer-facing call events
`peerConnectionEvents` (`types.ts:82-100`): `call-added`, `call-removed`, `call-invite`, `call-state-changed`, `call-rejected`, `call-cancelled`, `call-ended`, `call-timeout`.
- `call-added`: payload is the current `E2ECall | null` (`this.activeCall` getter, `sdk.ts:137-143`) — fired both from the router's `onCallCreated` callback (`sdk.ts:82-84`) and directly inside `acceptCall()` (`sdk.ts:288-291`).
- `call-invite`: payload `{ callId }` — see the dual-firing note in §5.4.
- `call-state-changed`: payload `CallLifecycleUpdate { state, callId, reason? }` (`types.ts:68-72`) — fired on every `updateCallLifecycle()` call; `CallLifecycleState` (`types.ts:28-43`) has 14 values: `idle`, `initiating`, `ringing`, `incoming`, `connecting`, `connected`, `ending`, `ended`, `rejected`, `no-peer`, `media-denied`, `signaling-failed`, `ice-failed`, `timeout`, `cancelled`. Of these, `media-denied` is never assigned anywhere in the read sources (no `getUserMedia` rejection handler sets it — a `getUserMedia` failure in `peer.ts:152-154` is not caught there at all and would reject `localStreamAcquisatonPromise` uncaught).
- `call-rejected` / `call-cancelled` / `call-timeout` / `call-ended`: payload `{ callId }` (`call-ended` additionally includes `reason`) — fired only from the *receiving* side of `handleCallSignal()` (i.e., when the peer's control signal arrives); a locally-initiated `rejectCall()`/`cancelCall()`/`endCall()` goes straight to `endLocalCall()` without firing the corresponding local `call-rejected`/`call-cancelled`/`call-ended` event for the caller of that method — only `call-state-changed`/`call-removed` fire locally in that case.
- `call-removed`: no payload; fired on every call termination path (local- or remote-caused) inside `endLocalCall()`.
- `signaling-failed` lifecycle state: set when `handleRawWebrtcSignal` rejects (decrypt/strategy-mismatch failure) — `sdk.ts:515-519` — i.e. a corrupted/foreign-strategy/replayed-below-threshold signaling envelope surfaces as this lifecycle state rather than a thrown error visible to the consumer.
