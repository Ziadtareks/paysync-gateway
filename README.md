<div align="center">

# 📲 PaySync Gateway

**Turn any Android phone into a 24/7 payment-verification gateway for Egyptian mobile wallets.**

The app polls your backend for pending deposits, matches them against incoming
wallet SMS (Vodafone Cash, InstaPay/NBE, BM, CIB, …), and dispatches
`confirmed` / `timeout` results back — fully automatic, all amounts in **EGP**.

[![Download APK](https://img.shields.io/badge/⬇️_Download-APK-4F46E5?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Ziadtareks/paysync-gateway/releases/latest/download/PaySync-Gateway.apk)
[![Releases](https://img.shields.io/badge/All_releases-here-1F2937?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Ziadtareks/paysync-gateway/releases)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)
![UI](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=android&logoColor=white)
![Tests](https://img.shields.io/badge/unit%20tests-18%20passing-brightgreen)

Sideloaded open-source build (MIT) — **not** distributed via Google Play.

</div>

---

<!-- 📸 Screenshots: drop 2–3 phone screenshots here (Dashboard + Settings),
     like NewPipe/Aegis do. Put the files in a screenshots/ folder, then:
<img src="screenshots/dashboard.png" width="200" /> <img src="screenshots/settings.png" width="200" />
-->

## 📥 Download & Install

1. Download the latest APK:
   **[PaySync-Gateway.apk](https://github.com/Ziadtareks/paysync-gateway/releases/latest/download/PaySync-Gateway.apk)**
   (always points to the newest release).
2. On the phone, open the file and allow installing from **unknown sources**
   when prompted (required for any app outside the Play Store).
3. Grant the SMS + notification permissions on first launch.

**Verify the signature** (optional, apksigner from Android build-tools):

```bash
apksigner verify --print-certs PaySync-Gateway.apk
# SHA-256: b28b28630e30714332f0857bd8d13380e5af7294bfb2cde6475ef64fa52c8748
```

## ✨ What is it?

Selling digital goods or running a Telegram store in Egypt usually means one
thing: waiting for the customer's wallet transfer, then manually checking the
SMS to confirm the payment. **PaySync Gateway** removes the human from that
loop — install it on the phone that receives the wallet SMS, point it at your
backend, and it becomes an always-on bridge.

Built with Kotlin + Jetpack Compose (Material 3), Room, WorkManager and
OkHttp · minSdk 26 · full EN/AR UI with RTL.

```mermaid
flowchart LR
    A["👤 Customer pays<br/>EGP to the wallet"] --> B["🖥️ Your backend<br/>creates a pending deposit"]
    B -->|"GET /transactions/pending<br/>every 15 s"| C["📲 PaySync Gateway<br/>on the merchant's phone"]
    D["💬 Wallet SMS arrives<br/>amount · reference · sender"] --> E["🧾 SMS parser<br/>AR/EN · digit-normalized"]
    C --> F{"🎯 VerifyMatcher"}
    E --> F
    F -->|"reference or<br/>amount ± 0.01 EGP"| G["📤 POST /transactions/dispatch<br/>status: confirmed"]
    F -->|"no match within<br/>timeout"| H["📤 POST /transactions/dispatch<br/>status: timeout"]
    G --> I["✅ Backend credits<br/>the customer"]
    H --> J["⏳ Backend expires<br/>the order"]
```

## 🚀 Features

| | |
|---|---|
| 🔄 **Always-on** | Foreground service (`dataSync`) polls every 15 s; WorkManager backup poller every 15 min; auto-restart on boot |
| 🌍 **Bilingual** | Full EN/AR UI, automatic RTL, in-app language switcher + Android 13+ system picker |
| 🧾 **Smart SMS parsing** | Vodafone Cash / InstaPay-NBE exact regexes + tolerant fallbacks + generic parser for any bank |
| 🎯 **Two-tier matching** | Exact reference match, then provider + amount tolerance (±0.01 EGP), race-safe behind a mutex |
| 📬 **Reliable outbox** | Dispatches persisted in Room; retry `2s → 64s` (max 6); 4xx dead-letters; Idempotency-Key on every POST |
| 🔐 **Secrets stay secret** | URL/secret/senders in `EncryptedSharedPreferences`, excluded from cloud backups, HMAC-signed dispatches |
| 📊 **Live dashboard** | Pulsing status card, network + battery truth, pending/queue metrics, live dispatch log |

## 📡 Backend API

The app talks to **exactly two endpoints** on your server:

```http
GET  {base}/transactions/pending   → JSON array of pending deposits
POST {base}/transactions/dispatch  → confirmed / timeout results
Headers: X-Gateway-Secret · X-Gateway-Signature (HMAC-SHA256) · Idempotency-Key
```

Field-by-field contract, retry semantics and server checklist:
**[`BACKEND_API_CONTRACT.md`](BACKEND_API_CONTRACT.md)**

## 🤖 No backend yet? Generate one with AI

The [`prompts/`](prompts/) folder ships **ready-made prompts** — paste one into
ChatGPT / Claude / Gemini and get a working backend in minutes:

| File | Builds you |
|---|---|
| [`telegram-bot-prompt.md`](prompts/telegram-bot-prompt.md) | A complete **Telegram store bot** with wallet top-ups |
| [`website-backend-prompt.md`](prompts/website-backend-prompt.md) | A **top-up website** (order page → auto-confirmed from the SMS) |
| [`generic-backend-prompt.md`](prompts/generic-backend-prompt.md) | Just the **2 API endpoints** on a stack you already have |

Full walkthrough (including how to enter the URL + secret in the app):
[`prompts/README.md`](prompts/README.md).

## 📱 Connect your backend in the app

1. **Settings** tab → paste your backend URL in **Bot API URL**.
2. Paste the same **Webhook Secret** your backend checks.
3. Add your wallets to **Allowed senders** — the exact SMS sender IDs
   (e.g. `VF-Cash`, `BanK-AlAhly`), using the same labels your backend sends
   in the `provider` field.
4. **Save** → toggle the gateway **ON** on the Dashboard.
5. Allow the **battery-optimization exemption** when asked.

> Testing locally? Run the backend on your PC and enter
> `http://<your-pc-ip>:8000` — cleartext HTTP is allowed for local
> development. Use HTTPS in production.

## 🔐 Security

- Settings (URL, secret, senders) live in **EncryptedSharedPreferences** and
  are excluded from cloud backups and device transfers.
- Every dispatch is **HMAC-SHA256-signed over the raw body**; the secret never
  leaves the device except as the auth header, and an **Idempotency-Key** makes
  server-side retries safe.
- `.gitignore` blocks keystores and `.env` files; no secrets are committed.

## 🛠️ Build from source

**Prerequisites:** Android Studio (Ladybug+) with **JDK 17**, a physical
Android 8.0+ device (SMS receivers are unreliable on emulators).

```bash
git clone https://github.com/Ziadtareks/paysync-gateway.git
cd paysync-gateway
./gradlew :app:testDebugUnitTest   # 18 tests — parser, matcher, HMAC
./gradlew :app:assembleDebug       # debug APK
```

> **Windows CLI:** set `JAVA_HOME` to a JDK 17 — the project does not build on
> JDK 21+.
>
> **Releases:** maintainers build the signed APK (release variant signs from a
> local, gitignored `keystore.properties`) and attach it with
> `gh release create vX.Y.Z PaySync-Gateway.apk`.

## 🤝 Contributing

PRs welcome! Keep regex changes covered by the sample-based unit tests, no new
DI frameworks, no Play-services dependencies, and run
`./gradlew :app:testDebugUnitTest` before opening a PR.

## ⚠️ Disclaimer

This project is an independent, open-source tool for automating **your own**
payment confirmations on a device **you own**. It is not affiliated with or
endorsed by Vodafone, Orange, Etisalat, WE, NBE, CIB, Banque Misr, InstaPay,
or any other provider. Always obtain consent before connecting a device, and
follow the terms of service of your wallet provider and local regulations.

## 📄 License

[MIT](LICENSE) © 2026 PaySync Gateway contributors
