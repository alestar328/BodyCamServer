# Resumable evidence upload — client contract v1

**Audience:** Nexus backend. This is what the two Android clients already
implement and validate against a stub server, so that switching to the real
endpoint is a configuration change and nothing else.

**Apps:** `BodyCamServer` (bodycam unit) and `AeriaNexusPrototype` (phone). Both
speak the same protocol with the same code.

**Reference server:** `tools/tus_stub_server.py` in this repo — ~350 lines of
Python standard library, no dependencies. It is the executable version of this
document; if anything here is ambiguous, that file is the answer.

---

## 1. Why resumable

Today a closed incident is a single MP4 of up to ~2 GB, encrypted before it
leaves the device (`docs/CRYPTO-FORMAT.md`), and uploaded over mobile data. The
current endpoint takes the whole file in one `POST multipart/form-data`, so a
connection drop at 1.9 GB costs the full 1.9 GB again. Body-worn units lose
connectivity constantly; this is not an edge case.

The protocol below is **tus 1.0.0 — Core plus the Creation extension**, with one
small non-tus addition for integrity confirmation (§4). We chose tus because it
is a published spec with existing server implementations (tusd, and native
libraries for most frameworks), so **you do not have to implement it by hand** —
putting `tusd` in front of your existing ingestion with a `post-finish` hook is
a valid answer to this document, and probably the cheapest one.

We are **not** using the `tus-java-client` library on the device. Two reasons
worth knowing, because they shaped the wire behaviour in §3.

---

## 2. Vocabulary

| Term | Meaning |
|---|---|
| **Session** | One server-side upload in progress, addressed by its own URL |
| **Offset** | How many bytes of the file the server currently holds. The single source of truth for resumption — the client always asks, never assumes |
| **Fingerprint** | Client-side id of the *content*: `sha256` of the exact bytes being uploaded. Never sent as such; it is how the client remembers which session belongs to which file |

Every request carries `Tus-Resumable: 1.0.0`. Every response must echo it.

Every request carries `Authorization: Bearer <token>`. Today that token is a
stub constant — the officer login (AUTH-001) does not exist yet. The client
already sends the header, so wiring the real token later does not touch the
transport.

---

## 3. The four operations

### 3.1 Create — `POST /files/`

```
POST /files/ HTTP/1.1
Tus-Resumable: 1.0.0
Authorization: Bearer <token>
Upload-Length: 1073741824
Upload-Metadata: incident_id SU5DXzAwMDAwOQ==,filename ...,sha256_cipher ...
Content-Length: 0
```

`Upload-Metadata` is the tus format: comma-separated `key <base64(value)>`
pairs, values UTF-8. Keys we send are listed in §5.

**Expected response:**

```
HTTP/1.1 201 Created
Tus-Resumable: 1.0.0
Location: /files/b45f9df9176a4d709a17ba40523851ae
```

`Location` may be relative or absolute; the client resolves it against the base
URL. It is opaque to the client — any stable, unguessable id works.

### 3.2 Ask — `HEAD <session>`

Sent **before every chunk**, not only after failures. After an aborted request
the server may hold part of a chunk the client never got acknowledged, so the
client's own count is not trustworthy.

```
HTTP/1.1 200 OK
Tus-Resumable: 1.0.0
Upload-Offset: 25165824
Upload-Length: 1073741824
```

`404` (or `410`) means the session no longer exists — expired, garbage
collected, server rebuilt. The client treats this as a normal outcome: it
forgets the stored URL and creates a new session. **Please return 404 rather
than a 500** when you drop old sessions.

### 3.3 Send — `PATCH <session>`

```
PATCH /files/b45f9df9... HTTP/1.1
Tus-Resumable: 1.0.0
Authorization: Bearer <token>
Upload-Offset: 25165824
Content-Type: application/offset+octet-stream
Content-Length: 8388608
```

```
HTTP/1.1 204 No Content
Tus-Resumable: 1.0.0
Upload-Offset: 33554432
```

Default chunk is **8 MiB**; it is configurable per device and you should not
depend on it. The last chunk is short. Each `204` is a resumption point that
survives a reboot of the unit and a reinstall of the app.

Two client behaviours that differ from the stock Java tus client, both
deliberate:

- **Every PATCH carries a real `Content-Length`.** `tus-java-client` uses
  `setChunkedStreamingMode(0)`, which sends `Transfer-Encoding: chunked` with no
  length and triggers `Expect: 100-continue`. That combination is what corporate
  proxies and WAFs break on. We know the chunk size in advance, so we declare
  it. **Your endpoint must accept a plain, length-delimited body.**
- **`X-HTTP-Method-Override: PATCH` on a `POST`** is used as a fallback where
  `PATCH` is unavailable or filtered. Accepting it is cheap; the stub does.

`409 Conflict` with an `Upload-Offset` header is the correct answer when the
client's offset does not match yours. The client re-reads the offset and
repositions instead of retrying blindly.

### 3.4 Confirm — `GET <session>/status`

**This is our addition, not part of tus.** See §4.

```json
{
  "id": "b45f9df9176a4d709a17ba40523851ae",
  "offset": 1073741824,
  "length": 1073741824,
  "complete": true,
  "verified": true,
  "sha256_received": "93aafe4b0f65…",
  "metadata": { "incident_id": "INC_000009", "…": "…" }
}
```

If you do not implement it, return `404` — the client logs that the server does
not verify and still marks the upload as delivered. Nothing breaks. But read §4
before deciding not to.

---

## 4. Integrity: why `verified` matters

The uploaded file is **AES-256-GCM encrypted evidence** (`.fev`,
`docs/CRYPTO-FORMAT.md`). You cannot inspect it, transcode it, or validate that
it is a playable video — by design, and without the private key you never will.

So the hash is the *only* thing the server can check, and it is exactly what
chain of custody needs: the client declares `sha256_cipher` in `Upload-Metadata`
at create time, you compute SHA-256 over the assembled bytes at finish time, and
`verified` says whether they match.

- `true` — stored bytes are byte-for-byte what left the device.
- `false` — **integrity incident, not a network failure.** The client keeps the
  local copy, writes it to the incident's receipt, and raises it in the log.
- `null` / endpoint absent — server does not verify; upload still counts as
  delivered.

This is also the gate for local retention (EVD-007): the unit cannot safely
delete its only copy of an incident until the server has confirmed the hash.

Note we do **not** use the tus `checksum` extension. Its Java client does not
implement it despite what the README suggests (verified against the 0.5.1
source: `Upload-Checksum` is never sent), and per-chunk checksums are not what
we need anyway — we need one hash over the finished file.

---

## 5. Metadata keys

Sent on create, same for both apps.

| Key | Example | Notes |
|---|---|---|
| `kind` | `evidence` \| `manifest` | Two uploads per incident, see §6 |
| `incident_id` | `INC_000009` | Monotonic per device, gaps are meaningful |
| `filename` | `36975_20260826_1731.mp4.fev` | Officer badge, date, time |
| `sha256_cipher` | 64 hex | Of the uploaded bytes — what §4 verifies |
| `sha256_plain` | 64 hex | Of the original MP4, for chain of custody. Empty for manifests |
| `encrypted` | `true` | `false` means encryption failed on the device and the file is plaintext MP4 — worth flagging on your side |
| `crypto_format` | `FEVD1` \| `none` | |
| `officer_code` | `36975` | Badge. Stub identity today (no officer login yet) |
| `officer_name` | `John Smith` | Stub identity today |
| `device_id` | Android id | |
| `device_model` | | |
| `uploaded_at` | `2026-08-26T17:31:04Z` | |
| `latitude` / `longitude` | | Omitted when unknown, never sent as `0.0` |

Keep the header limit in mind: this is ~600 bytes, well under the usual 8 KB.

---

## 6. Two uploads per incident, in this order

1. **`manifest.json`** (`kind=manifest`, a few KB) — segment timeline, pre-roll
   window, trigger offset, officer identity, both hashes, and the crypto block.
   It goes first on purpose: it is small, it arrives immediately, and it tells
   you what is coming and what hash to expect.
2. **The evidence file** (`kind=evidence`) — the `.fev`, or the plaintext MP4 if
   encryption failed.

Both are tied together by `incident_id`. An incident with a manifest and no
evidence is an upload still in flight — expected, not an error.

---

## 7. Running the stub

```bash
python tools/tus_stub_server.py --dir ./uploads --port 1080

# reproduce a mid-upload connection drop, once per upload, to see the client resume:
python tools/tus_stub_server.py --fail-at 12582912
```

Point a device at it with `FalconOne/upload.conf` on the unit:

```
base_url=http://192.168.1.40:1080/files/
token=stub-token
```

`GET /files` lists everything received with its offset and verification result.

---

## 8. What we need from you

1. **Confirm the four operations above** — or tell us where yours differ. The
   transport layer is one class in each app; the queueing, resumption and
   verification logic sitting on top of it does not change either way.
2. **The real base URL**, and whether it is `tusd` in front of the existing
   ingestion or a native implementation. If `tusd`: the `post-finish` hook is
   where the manifest and the evidence get handed to the existing pipeline.
3. **Session lifetime.** How long an interrupted upload can be resumed. The
   client currently gives up on a stored session after 7 days.
4. **Whether you will implement `GET /status`** (§4). If not, we lose
   server-side integrity confirmation, and with it the precondition for deleting
   the local copy on the unit.
5. **Auth.** What the `Authorization` token should be once officer login exists,
   and whether it is per-device or per-officer.

Independently of this document, and going to cyber security rather than to
backend: we still need **the real Nexus public key** (RSA-2048 SPKI, DER or PEM,
plus a `kid`), generated on your infrastructure so the private key never leaves
it. Until it arrives, evidence is encrypted for a development key whose `kid`
starts with `dev-`, and anything encrypted for a `dev-` key is test material by
definition.
