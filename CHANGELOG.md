# Changelog

All notable changes to PaySync Gateway are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.2.0] — 2026-10-05

Correctness, security and reliability fixes from a full code review.
**Upgrade note for backend owners:** the retry policy changed (see below) and
a new, optional signature header exists — existing backends keep working
without any change.

### Fixed — payments

- **A matched deposit could be confirmed twice, or get `timeout` after
  `confirmed`.** The matched row was deleted right away, so the next poll
  (before the backend had received the dispatch) re-inserted it as a fresh
  PENDING deposit. Matched and timed-out deposits are now kept as tombstones
  until their dispatch is delivered (plus 24 h).
- **A reference match ignored the amount.** A customer could supply the
  reference of a 1 EGP transfer for a 1000 EGP deposit. A reference now
  confirms only when the amount also agrees (±0.01 EGP); otherwise the SMS is
  logged for manual review.
- **Confirmed payments were dropped after ~2 minutes of backend errors**
  (6 failed tries, or any 401/403). Dispatches now stay queued until the
  backend accepts them; only `400/404/409/410/422` are dead-lettered.
- **SMS that arrived before the deposit reached the phone were never matched.**
  Unmatched SMS are now re-checked against newly polled deposits — by amount
  only within the poll lag (poll interval + 60 s), by exact reference up to
  15 minutes back, so an old unmatched SMS is never credited to a different
  customer's new deposit of the same amount.
- **Outgoing transfers / debits were parsed as deposits** (e.g. "تم تحويل
  مبلغ 150 جنيه إلى رقم …", "تم خصم …", "You have sent …"), which could
  auto-confirm a pending deposit of the same amount. They are now ignored.
- **Transaction references were corrupted** when a number followed them
  ("Transaction ID: 023732288590 2024-10-05" → "0237322885902024"): spaces
  are no longer stripped during digit normalization.
- The generic bank parser no longer picks a balance ("رصيدك 5000 جنيه") as
  the amount or the sender's mobile number as the reference.

### Fixed — reliability

- One malformed deposit in `GET /transactions/pending` no longer aborts the
  whole poll; invalid items are skipped individually.
- HTTP timeouts are real (`callTimeout`, cancellable calls) instead of a
  coroutine timeout around a blocking call.
- Drains are serialized and no longer cancel an in-flight POST.
- A failure while processing an SMS releases its dedup claim instead of
  marking the payment "seen" forever.
- The SMS match, outbox insert and "matched" marks happen in one database
  transaction; the expiry sweep runs under the match lock.
- If the foreground service cannot start, the app stops cleanly, engages the
  WorkManager fallback and notifies the user instead of running half-alive.

### Changed — settings storage

- **Settings are now encrypted by the app itself** (AES-256-GCM, key
  generated in and never leaving the Android Keystore, each value bound to
  its key name) instead of the deprecated AndroidX `security-crypto`
  `EncryptedSharedPreferences`.
- **One-time, crash-safe migration** on the first launch after the update:
  old settings are copied, re-read and verified, and only then is the old
  file deleted. Any failure rolls the copy back, keeps the old file, keeps
  the app running on it, and retries on the next launch. Values saved while
  a migration was pending are never overwritten by older ones.
- `security-crypto` remains only as a read-only legacy reader for that
  migration and will be removed in a later release.

### Fixed — security & privacy

- New `X-Gateway-Timestamp` + `X-Gateway-Signature-V2` headers sign every
  request (GET and POST) without sending the secret. The raw
  `X-Gateway-Secret` header can be switched off in Settings (default ON for
  compatibility). See `BACKEND_API_CONTRACT.md`.
- Settings can no longer be saved without a secret.
- If the Keystore is broken, settings go to a separate unencrypted file (with
  a warning in Settings) instead of reading the encrypted file as plain text.
- Removed the unused `READ_SMS` permission.
- Release builds without `keystore.properties` are now unsigned instead of
  being signed with the public debug key.
- PRIVACY.md no longer claims the raw SMS text is sent to the backend (it
  never was).

### Fixed — UI

- Dashboard "Last poll" now reflects the service's own polls, not only the
  manual "Poll now" button.
- "Poll now" no longer reports success when it was offline or not configured.
- Removing the last allowed sender no longer silently restores the defaults.
- Out-of-range poll intervals are clamped and shown instead of being ignored.
- Live Log entries that need a human (over the auto-confirm cap, reference
  with the wrong amount, ambiguous) show "Needs review" instead of "Timeout".

### Removed

- Stray `backend_config.json` test fixture from the repository root.

## [1.1.1] — 2026-10-01

Fix release for a reliability bug found during the v1.1.0 upgrade test.

### Fixed

- **The gateway now resumes by itself after an app update.** Android kills the
  24/7 foreground service during an in-place update while the toggle stayed
  ON — the Dashboard kept saying "Running" with nothing actually running.
  On `MY_PACKAGE_REPLACED` (an officially exempted broadcast for background
  foreground-service starts) the service now restarts when the toggle is ON;
  if a start is ever denied, the app falls back to the WorkManager poller and
  a high-priority "Gateway stopped after update, tap to resume" notification.
- **The Dashboard status is honest.** The status now reflects whether the
  service is actually alive (fresh service heartbeat), not just the persisted
  toggle: "Running" (green), "Stopped" (off), and a new amber "Gateway
  stopped after update" state with a one-tap **Restart Gateway** button.
- **The "Gateway is not verifying payments" alert now also fires when the
  service is dead while the toggle is ON** (detected by the 15-minute
  WorkManager poller watchdog). Same 5-minute threshold and 30-minute rate
  limiting as before.

### Notes

- Wire protocol unchanged; settings and database are preserved by the update.
  New settings key (`service_heartbeat_ms`) is additive; Room schema unchanged.

## [1.1.0] — 2026-09-30

Security- and reliability-hardening release. Wire protocol unchanged —
existing backends keep working without any change. Upgrade installs over
v1.0.0 keep all settings.

### Matching safety

- Amount fallback (provider + amount ±0.01 EGP) now confirms a deposit **only
  when exactly one** pending deposit matches. Zero or 2+ candidates are
  logged as *ambiguous* in the Live Log and never auto-confirmed.
- Duplicate transaction references (2+ live deposits with the same reference)
  are likewise ambiguous and never confirmed.
- Reference matching normalizes Arabic-Indic/Persian digits, whitespace and
  punctuation before exact (never substring) comparison.
- One SMS can confirm at most one deposit, ever: persisted SHA-256
  (sender+body+timestamp) fingerprints survive restarts, duplicate
  broadcasts and re-scans; matching is serialized behind the mutex with the
  claim made inside the lock.
- New persisted settings (defaults preserve v1.0.0 behavior):
  - **Amount fallback matching** (ON by default) — turn OFF to confirm only
    on an exact transaction reference.
  - **Max auto-confirm amount (EGP)** (disabled by default) — payments above
    the cap are logged as *needs manual review* and follow their normal
    timeout instead of dispatching `confirmed`.

### SMS source trust

- Sender gate is an exact, case-insensitive match against the allow-list;
  near-misses (prefix/suffix variants, numeric senders, whitespace) are
  dropped before any parsing.

### Network & signing

- Release builds **reject cleartext HTTP**; the URL field in release builds
  accepts `https://` only. Cleartext remains possible in debug builds for
  local LAN testing.
- HMAC-SHA256 signature scheme unchanged (backward compatible); the backend
  contract now documents server-side `Idempotency-Key` uniqueness as the
  replay defense.
- Proven by test that the signature covers the exact raw bytes received by
  the server.
- No transaction or sender data reaches Logcat in release builds
  (data-bearing logs are debug-only).

### 24/7 reliability

- Foreground service uses the `specialUse` type on Android 14+ — no
  6-hour/24-hour `dataSync` limit, no Android 15 boot-restart restriction
  (`dataSync` kept for Android 9–13).
- High-priority "Gateway is not verifying payments" alert after 5 minutes of
  continuous poll failures (service loop and 15-minute WorkManager poller),
  rate-limited to one alert per 30 minutes, auto-cleared on recovery.
- OEM background-restriction guidance (Xiaomi, Samsung, Oppo/Realme,
  Huawei/Honor) in the Permissions screen and README.
- Restricted-settings recovery steps for Android 13+ sideloaded installs.

### Release readiness

- R8 (minify + shrinkResources) enabled with keep rules; `allowBackup=false`
  and backup/device-transfer rules exclude the Room database and settings.
- New: [PRIVACY.md](PRIVACY.md) (EN + AR) and an **opt-out update check**
  (at most one anonymous GitHub API request per day; never downloads or
  installs anything; errors silent).
- `prompts/` now carries a prominent security warning plus a mandatory
  checklist for AI-generated backends handling real money.
- README states honestly that SMS sender IDs are not cryptographic proof and
  recommends the max auto-confirm amount plus periodic statement
  reconciliation.

### Development

- CI runs unit tests, lint, debug + R8 release builds, and an instrumented
  test suite on API 34/35 emulators.
- 42 unit tests and 18 instrumented tests (SMS end-to-end against a fake
  backend, outbox retries/dead-letters, Room schema integrity, settings
  upgrade, foreground service + health alert).
- Room schema `4.json` committed; blanket destructive migration removed
  (future schema changes require an explicit migration).
- Test fixtures scrubbed of real phone numbers and names.

## [1.0.0] — 2026-09-28

Initial public release: 24/7 SMS payment-verification gateway for Egyptian
wallets (Vodafone Cash, InstaPay/NBE, generic banks), bilingual EN/AR UI,
HMAC-signed dispatches with Idempotency-Key, Room outbox with retry, WorkManager
backup polling, boot auto-restart.
