# Prompt — Add the PaySync Gateway API to an existing project

> **How to use:** copy everything below the line and paste it into your AI
> assistant **inside your existing project** (Cursor / Copilot / Claude Code /
> chat). Replace the `[bracketed]` parts first. Use this when you already have
> a site or bot and just need the two endpoints the **PaySync Gateway**
> Android app talks to.

---

I have an existing project: **[describe your stack, e.g. "Django 5 +
PostgreSQL" / "Next.js API routes + Supabase" / "Laravel 11"]**.

Add a **PaySync Gateway integration**: a small module that lets the PaySync
Gateway Android app verify wallet deposits (all amounts **EGP**) against my
existing data. The app is already built; it talks to exactly **2 HTTP
endpoints** on my server. Config comes from env: `GATEWAY_SECRET`
(generate me a strong one), `PORT` if applicable.

## What a "pending deposit" means in my domain

`[describe, e.g. "a row in the `orders` table with status='awaiting_payment',
amount_egp, wallet_provider, id" / "create a `pending_deposits` table for me"]`.
The integration must expose it like this:

### 1. `GET /transactions/pending`

- Auth: header `X-Gateway-Secret` must equal `GATEWAY_SECRET` (constant-time
  compare) → else `401`.
- Returns `200` with a **bare JSON array** (never wrapped in an object) of all
  pending, unfinalized, unexpired deposits:
  ```json
  [
    { "verify_id": "550e8400-…", "expected_amount": 150.0,
      "provider": "VF-Cash", "reference_id_hint": "", "timeout_ms": 120000 }
  ]
  ```
- `verify_id` = my unique id for the deposit; `provider` = wallet label string
  stored and echoed **as-is**; `reference_id_hint` = `""` when unknown;
  `timeout_ms` optional (the app defaults to 120000 ms).
- `[]` when idle. Polled every ~15 s — keep it cheap, respond within 25 s.

### 2. `POST /transactions/dispatch`

Headers: `X-Gateway-Secret`, `X-Gateway-Signature`, `Idempotency-Key`.
JSON body:

```json
{ "verify_id": "…", "status": "confirmed",
  "amount": 150.0, "provider": "VF-Cash", "reference_id": "0237…" }
```
or `{ "verify_id": "…", "status": "timeout" }`

Behavior, exactly in this order:

1. `X-Gateway-Secret` mismatch → `401`.
2. Recompute **HMAC-SHA256** over the **raw request body bytes** (UTF-8),
   keyed with `GATEWAY_SECRET`, lowercase hex; compare with
   `X-Gateway-Signature` → `401` on mismatch. (GET has no signature.)
3. Idempotency: store every processed `Idempotency-Key`; a repeat returns the
   original response without side effects.
4. Unknown `verify_id` → `404` (the app stops retrying on 404).
5. Already-finalized deposit → `200 {"status":"success"}` with **no** side
   effects (never credit twice).
6. `status:"confirmed"` → finalize/credit my order (optionally assert
   `|amount − expected_amount| ≤ 0.01` first), return
   `200 {"status":"success"}`.
7. `status:"timeout"` → expire/release the deposit, return
   `200 {"status":"success"}`.
8. Do **not** return accidental `4xx` from this route (except deliberate
   `401`/`404`) — any `4xx` except `429` makes the app drop the dispatch
   forever. `429`/`5xx`/timeouts are retried by the app, which is safe only
   because of the idempotency layer.

## Requirements

- Match my existing code style, folder structure, router and ORM/migrations.
- Add an `idempotency_keys` table/migration if needed.
- Write it as a self-contained module (router + service + storage), wired into
  my app, with no changes to unrelated code.
- Output the exact steps to run it and to test with `curl`:
  a `GET` example with the secret header, and a signed `POST` example
  (show me a one-liner that computes the HMAC with `openssl` or Node/Python).
- Finish with the checklist: bare-array pending endpoint · secret check ·
  HMAC check · idempotency · 404 on unknown id · no double credit · all
  responses < 25 s · secret only from env.
