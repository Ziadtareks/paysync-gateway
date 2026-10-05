# Real-device test checklist (v1.2 hardening release)

Run this on **real phones after CI is green**, ideally two different brands
(e.g. one Samsung + one Xiaomi/Oppo/Realme). OEM Keystore implementations
differ, and that is exactly what an emulator cannot show you.

Tip: a **debug** build lets you inspect files and see all logs:
`adb logcat -s SettingsStorage EncryptedPrefs GatewayRepo DispatchWorker SmsReceiver`.
Release builds only log warnings/errors (no output = nothing went wrong).

## 1. Keystore & settings migration (most important)

Upgrade path — this is what every existing user goes through:

- [ ] Install the **current public v1.1.1 APK** on the phone.
- [ ] In Settings enter: Bot API URL, webhook secret, add one extra allowed
      sender, set poll interval to e.g. 30 s, turn the gateway ON.
- [ ] Install the **new APK over it** (do NOT uninstall).
- [ ] Open the app → Settings: URL, secret, senders, poll interval are all
      exactly as before. **No red "settings are stored unencrypted" warning.**
- [ ] Dashboard shows the gateway running (it resumes by itself after the update).
- [ ] Force-stop the app, reopen → settings still there (migration is not
      repeated, nothing lost).
- [ ] Reboot the phone → settings still there, gateway auto-starts.
- [ ] *(debug build only)* `adb shell run-as com.paysync.gateway ls shared_prefs`
      → `paysync_settings_v2.xml` exists, `paysync_secure_prefs.xml` is gone;
      `adb shell run-as com.paysync.gateway cat shared_prefs/paysync_settings_v2.xml`
      → values look like `v1:…` and your secret is **not** readable.

Fresh install:

- [ ] Uninstall, install the new APK, configure from scratch → works, no warning.

Edge cases (do at least on one phone):

- [ ] Change/remove the screen lock (PIN → none → PIN), reopen → settings
      still readable (our key does not require user authentication).
- [ ] Clear the app's **storage** in system settings → app starts clean, can
      be configured again (no crash).

If anything here fails: send the `adb logcat` lines for tags
`SettingsStorage` / `EncryptedPrefs` and the phone model + Android version.

## 2. Payments end to end (with your real backend or a test backend)

- [ ] Create a pending deposit, pay the exact amount → **confirmed** within
      seconds; backend credited once.
- [ ] Pay **before** the deposit is created/polled (within ~1 min) → still
      confirmed on the next poll (late matching).
- [ ] Send money **out** of the merchant wallet for the same amount as a
      pending deposit → it is **not** confirmed (outgoing SMS ignored).
- [ ] Deposit with a reference hint, pay a different amount using that
      reference → **not** confirmed, Live Log shows **"Needs review"**.
- [ ] No payment → **timeout** after the configured time, sent once.
- [ ] Stop the backend (or return 500), get a payment confirmed, wait 5+ min,
      start the backend again → the confirmation is delivered (queue count on
      the Dashboard goes back to 0). Nothing is lost.

## 3. Security settings

- [ ] Settings refuses to save with an empty secret.
- [ ] Once your backend verifies `X-Gateway-Signature-V2`, turn **OFF**
      "Send raw secret header (legacy)" → payments still confirm.

## 4. Permissions & background

- [ ] Fresh install asks only for SMS (receive) + notifications — no
      "read SMS" permission prompt.
- [ ] Leave the phone idle overnight with the gateway ON → still running in
      the morning; a test payment confirms.
