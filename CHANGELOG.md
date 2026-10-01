# Changelog

All notable changes to PaySync Gateway are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
