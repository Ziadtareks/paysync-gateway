# PaySync Gateway — Backend API Contract

This document tells backend developers exactly what to add to their
**existing** server so the standalone **PaySync Gateway** Android app can
verify deposits against it. All amounts are strictly **EGP**.

The Android app is already complete. The user points it at any backend by
typing a **Bot API URL** and **Webhook Secret** in the app's Settings screen.
Implement the 2 endpoints below on your server and the app works with it.

- Base URL = the Bot API URL from Settings, with any trailing `/` trimmed.
  Example: if the user enters `https://api.mybot.com/`, requests go to
  `https://api.mybot.com/transactions/pending` (no double slash).
- Respond within **25 seconds** — the app times out slower responses and
  treats them as network failures.
- Currency is always EGP. Provider strings (e.g. `VF-Cash`) are opaque,
  user-defined labels: store them as-is and echo them back as-is.

## Authentication headers (app → server)

When a secret is configured, the app attaches it to **every** request:

| Header | Present on | Meaning |
|---|---|---|
| `X-Gateway-Timestamp` | `GET` + `POST` | Unix time in **seconds** when the request was signed. *(since v1.2)* |
| `X-Gateway-Signature-V2` | `GET` + `POST` | Lowercase-hex HMAC-SHA256, keyed with the shared secret, over the UTF-8 string `"v2\n{X-Gateway-Timestamp}\n{METHOD}\n{path}\n{Idempotency-Key}\n"` followed by the raw body bytes (empty for `GET`). `{path}` is the encoded request path plus `?query` if any (e.g. `/transactions/pending`); `{Idempotency-Key}` is empty on `GET`. Verify it and reject requests whose timestamp is more than **5 minutes** away from your clock. *(since v1.2)* |
| `X-Gateway-Signature` | `POST` only | Legacy: lowercase-hex HMAC-SHA256 of the **raw POST JSON body bytes** (UTF-8), keyed with the shared secret. Still sent for existing backends. |
| `X-Gateway-Secret` | `GET` + `POST` | Legacy: the raw shared secret. Sent only while **Settings → "Send raw secret header (legacy)"** is ON (the default, so existing backends keep working). |
| `Idempotency-Key` | `POST` only | UUID minted once per dispatch at match time. **You must dedupe on it**: a retried POST with a seen key must return the original result without executing twice. |

The app refuses to save a configuration without a secret. **Recommended:**
verify `X-Gateway-Signature-V2` (+ timestamp window) on every request, then
switch the legacy raw-secret header OFF in the app — the secret then never
travels over the network, and captured requests stop being replayable after
5 minutes. Backends that only check `X-Gateway-Secret` /
`X-Gateway-Signature` keep working unchanged while the legacy header is ON.

## Replay protection (server-side responsibility)

The legacy `X-Gateway-Signature` covers **only the raw body**, so a captured
legacy request can be replayed. `X-Gateway-Signature-V2` additionally covers
the timestamp, method, path and `Idempotency-Key` — enforce the 5-minute
timestamp window to bound replays. Either way, keep the
**`Idempotency-Key`** checks below: it is a UUID
minted once per dispatch at match time and never reused. Servers MUST enforce
its uniqueness:

- Store every `Idempotency-Key` you have processed (a 7-day retention is
  recommended — the app keeps retrying a failed dispatch until your server
  accepts it, e.g. through a long outage).
- A retried POST with a seen key must return the original result **without
  executing again** (this also makes the app's exponential-backoff retries
  safe).
- Optionally also reject `verify_id` values that were already finalized
  (credited or timed out) — the app never re-sends a finalized dispatch.

Because the same body+secret always produces the same signature, a captured
request is replayable within the constraints above; enforcing `Idempotency-Key`
uniqueness (and finalizing `verify_id` at most once) closes it.

## Endpoint 1 — List pending deposits

```http
GET {base}/transactions/pending
```

No request body. Returns `200` with a JSON array (one object per deposit
waiting for an SMS match):

```json
[
  {
    "verify_id": "550e8400-e29b-41d4-a716-446655440000",
    "expected_amount": 50.0,
    "provider": "VF-Cash",
    "reference_id_hint": "",
    "timeout_ms": 120000
  }
]
```

Field notes:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `verify_id` | string | yes | Your unique id for this deposit. The app echoes it back on dispatch. Never blank. |
| `expected_amount` | number | yes | Amount the customer should send, in EGP. |
| `provider` | string | yes | Wallet label (e.g. `VF-Cash`, `InstaPay`). Opaque to the app — use the same string your flow told the user to pay with. |
| `reference_id_hint` | string | no | Optional exact transaction reference, if your flow knows one. Empty string when unknown. Priority-1 matching key (see below). |
| `timeout_ms` | number | no | Per-request timeout override in milliseconds. Must be within 15000–900000 if sent. Omit it and the app uses its own default (**120000 ms**). |

Behavior notes:

- Return an **empty array** (`[]`) when nothing is pending.
- The app also accepts the array wrapped in an object under `pending`,
  `transactions`, `data`, or `items` — but prefer the bare array.
- Entries with a blank `verify_id` are ignored by the app.
- The app polls this endpoint roughly every **15 seconds** (plus a periodic
  background poll), so keep it cheap.
- Non-2xx responses are treated as temporary fetch failures (retried on the
  next poll); they do not delete anything.

## Endpoint 2 — Receive the verification result

```http
POST {base}/transactions/dispatch
Content-Type: application/json; charset=utf-8
X-Gateway-Secret: <secret>
X-Gateway-Signature: <hmac-sha256 hex of the raw body>
Idempotency-Key: <uuid>
```

A **confirmed** dispatch (SMS matched a pending request):

```json
{
  "verify_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "confirmed",
  "amount": 50.0,
  "provider": "VF-Cash",
  "reference_id": "123456789"
}
```

A **timeout** dispatch (no matching SMS arrived before the deadline):

```json
{
  "verify_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "timeout"
}
```

Field notes:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `verify_id` | string | yes | Which pending deposit this result is for. |
| `status` | string | yes | `confirmed` or `timeout`. Nothing else is ever sent. |
| `amount` | number | confirmed only | Amount parsed from the SMS, in EGP. |
| `provider` | string | confirmed only | The wallet label that matched the SMS sender. |
| `reference_id` | string | confirmed only | Transaction reference parsed from the SMS. May be absent if the SMS had none. |

What your server must do:

1. Check `X-Gateway-Secret` against your stored secret → `401` if wrong.
2. Recompute the HMAC-SHA256 hex over the raw request body with the secret
   and compare it to `X-Gateway-Signature` → `401` on mismatch.
3. Look up `Idempotency-Key`: if seen before, return the stored response
   **without executing again**.
4. If `verify_id` was already finalized (credited or timed out), return
   success marked as duplicate — **never credit the same `verify_id` twice**.
5. On `confirmed`: credit the user, then return success. You may sanity-check
   that the received `amount` is within **±0.01 EGP** of what you expected.
6. On `timeout`: release/expire the pending deposit and optionally notify
   the user. Return success.
7. On success return `200` with `{"status": "success"}` (extra fields are
   ignored by the app).

## Status codes and app retry behavior

The app treats responses as follows — implement accordingly:

| Your response | App behavior |
|---|---|
| `2xx` | Success. The dispatch is deleted from the app queue. |
| `400`, `404` (e.g. unknown `verify_id`), `409`, `410`, `422` | Dead-letter: dropped immediately, **never retried**. Use these only for permanently unprocessable dispatches. |
| Anything else: `401`/`403`, other `4xx`, `429`, `5xx`, no response / timeout | **Kept and retried until it succeeds**: 6 quick tries (2s → 32s backoff), then WorkManager retries in the background (every 30 s, growing, plus a 15-minute safety-net drain). A wrong secret or a server outage therefore never loses a confirmed payment — fix the problem and the queue delivers. Your `Idempotency-Key` dedupe makes these retries safe. |

## How matching works (context for backend developers)

You only need the 2 endpoints above, but this explains what the fields do:

1. Your server creates a pending deposit and exposes it via `GET`.
2. The app fetches it and waits for a wallet SMS on the device.
3. On SMS arrival the app parses amount / reference and matches:
   - **Priority 1:** exact `reference_id` match against your
     `reference_id_hint` (when you supply one) **and** the SMS amount within
     ±0.01 EGP of `expected_amount`. A matching reference with a different
     amount is never confirmed — it is logged for manual review and the
     deposit follows its timeout.
   - **Priority 2:** `provider` matches **and** the amounts agree within
     **±0.01 EGP**.
   - **Ambiguity guard:** if two or more live deposits share the same
     reference, or the amount fallback matches two or more live deposits,
     the app confirms **nothing** and logs the SMS as ambiguous for manual
     review. Always send a `reference_id_hint` when your flow knows the
     transaction reference — it is the only unambiguous key.
   - Outgoing / debit SMS ("تم تحويل … إلى", "تم خصم", "You have sent", …)
     are never treated as deposits.
   - **Late matching:** if the SMS arrived *before* the deposit reached the
     phone (poll lag, or the customer paid first), the app re-checks
     unmatched SMS against newly fetched deposits: by amount only for SMS
     younger than one poll interval + 60 s, by exact reference (+ amount) for
     SMS up to 15 minutes old. Another reason to send `reference_id_hint`.
4. On match the app POSTs `confirmed` (with the `Idempotency-Key` minted at
   match time). If the per-request `timeout_ms` (or the 120 s app default)
   elapses with no match, the app POSTs `timeout`. Each `verify_id` gets at
   most one of the two; the app remembers finalized ids for 24 h after
   their timeout so a deposit you still list is not picked up again.

## Implementation checklist

- [ ] `GET /transactions/pending` returns `200` + bare JSON array (empty array when idle).
- [ ] Both endpoints verify `X-Gateway-Signature-V2` + the 5-minute `X-Gateway-Timestamp` window (`401` on mismatch) — or, for legacy backends, `X-Gateway-Secret` / `X-Gateway-Signature`.
- [ ] `POST /transactions/dispatch` dedupes on `Idempotency-Key` (no double execution).
- [ ] `verify_id` is finalized at most once (no double credit on retries).
- [ ] Unknown `verify_id` returns `404` (so the app stops retrying it).
- [ ] Credit based on **your** stored `expected_amount`; treat the dispatch `amount` as informational and reject a mismatch beyond ±0.01 EGP.
- [ ] Success returns `2xx` with `{"status": "success"}`.
- [ ] Responses arrive within 25 seconds.
- [ ] Secret is stored server-side from your own config — never hardcoded in client-facing code.

## No backend yet?

Copy one of the ready-made AI prompts in [`prompts/`](prompts/) into
ChatGPT / Claude / Gemini to generate a working backend (Telegram store bot,
top-up website, or a minimal 2-endpoint module for your existing stack).
