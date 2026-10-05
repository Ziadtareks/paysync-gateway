# Privacy Policy — PaySync Gateway

**Last updated: 2026-09-29 (v1.1.0)** · [العربية أدناه](#سياسة-الخصوصية--بوليصة-الخصوصية)

PaySync Gateway is an open-source app (MIT) that runs **on your own phone**
and reports wallet payments **to a backend you configure yourself**. There is
no server operated by the developer and no account system.

## What the app reads

- **Incoming SMS from the senders you allow-list only.** Every incoming SMS
  sender is compared (exact match, case-insensitive) against the list you
  configure in Settings. SMS from any other sender is dropped before it is
  parsed — its content is never processed or stored.
- Nothing else on the device: no contacts, no call logs, no photos, no
  location, no other apps.

## Where data is sent

- Matched payment details (verify id, status, amount, provider label,
  transaction reference — never the raw SMS text) are sent **exclusively to
  the backend URL you enter in Settings** — your own server. The developer
  never receives a copy.
- Requests to your backend are signed with HMAC-SHA256 using the secret you
  configure (the raw secret header can be switched off in Settings once your
  backend verifies the V2 signature).

## What is stored on the device

- Settings (backend URL, secret, allowed senders, matching options) are stored
  in **EncryptedSharedPreferences** (if the device's Keystore is broken, the
  app falls back to a separate unencrypted file and shows a warning in
  Settings), excluded from all backups and device
  transfers.
- A local Room database: recent captured SMS (last 100), the dispatch outbox,
  a short dispatch log (last 50), and a 7-day dedup ledger of one-way SMS
  hashes (SHA-256 of sender+body+timestamp — not reversible into the message).
- All of it stays on the device. Cloud backup and device-to-device transfer of
  the database and preferences are disabled.

## Update check (optional, on by default)

If the **Check for updates** setting stays enabled, the app contacts
`api.github.com` (GitHub's public releases API) **at most once every 24 hours**
to compare the latest published release version with the installed one, and
shows a notice with a link if a newer version exists. **Nothing is ever
downloaded or installed automatically.** No device identifiers, settings, or
payment data are included in this request — it is a plain anonymous HTTPS GET.
Turn it off in Settings → Matching Safety → *Check for updates*. Errors
(offline, rate limit) are silently ignored.

## What is never collected

- No analytics, no crash reporting, no advertising, no tracking SDKs — there
  are none in the codebase.
- The developer receives **nothing**: no data is collected by or for the
  developer from any user, ever.

## How to delete your data

- In-app: **Dashboard → Clear** deletes captured SMS and dispatch logs.
- Complete removal: uninstall the app. Android deletes the app's databases,
  encrypted preferences and keystore-bound keys with it.
- SMS on the phone is never modified or deleted by the app — it only *reads*
  incoming SMS from allowed senders.

## Permissions used

| Permission | Why |
|---|---|
| `RECEIVE_SMS` | Receive incoming wallet SMS from your allow-listed senders (the inbox itself is never read) |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Talk to your backend and know when you are offline |
| `FOREGROUND_SERVICE*` | Keep the 24/7 verification service alive |
| `RECEIVE_BOOT_COMPLETED` | Restart the gateway after a reboot |
| `POST_NOTIFICATIONS` | Show the gateway status + "not verifying" alert |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Optional exemption so the gateway survives Doze |
| `WAKE_LOCK` | Keep the poll loop running |

---

# سياسة الخصوصية — PaySync Gateway

**آخر تحديث: ٢٠٢٦-٠٩-٢٩ (الإصدار 1.1.0)**

تطبيق PaySync Gateway مفتوح المصدر (رخصة MIT) يعمل **على هاتفك أنت** ويرسل
تفاصيل مدفوعات المحفظة **إلى سيرفر خلفي تضبطه بنفسك**. لا يوجد أي سيرفر
للمطوّر ولا أي نظام حسابات.

## ما يقرؤه التطبيق

- **رسائل SMS الواردة من المُرسِلين الذين تسمح بهم فقط.** يُقارن مُرسِل كل رسالة
  (مطابقة تامة غير حساسة لحالة الأحرف) بالقائمة التي تضبطها في الإعدادات، وأي
  رسالة من مُرسِل آخر تُهمَل قبل تحليلها — لا تُعالَج أو تُخزَّن أبدًا.
- لا شيء آخر على الجهاز: لا جهات اتصال، لا سجل مكالمات، لا صور، لا موقع،
  ولا تطبيقات أخرى.

## إلى أين تُرسل البيانات

- تفاصيل المدفوعات المتطابقة (معرّف الطلب، الحالة، المبلغ، اسم المزود، رقم
  المرجع — وليس نص الرسالة الأصلي أبدًا) تُرسل **حصريًا إلى رابط الـ Backend
  الذي تكتبه في الإعدادات** — سيرفرك أنت. المطوّر لا يستلم نسخة أبدًا.
- الطلبات إلى سيرفرك تُوقَّع بـ HMAC-SHA256 بمفتاحك السري (ويمكن إيقاف إرسال
  المفتاح نفسه في الهيدر من الإعدادات بعد أن يدعم سيرفرك توقيع V2).

## ما يُخزَّن على الجهاز

- الإعدادات (الرابط، المفتاح السري، المُرسِلون المسموح بهم، خيارات المطابقة)
  في **EncryptedSharedPreferences** مشفّرة ومستثناة من كل النسخ الاحتياطية
  ونقل البيانات بين الأجهزة.
- قاعدة بيانات محلية (Room): آخر ١٠٠ رسالة مُحلَّلة، طابور الإرسال، سجل قصير
  (آخر ٥٠ عملية)، وسجل منع تكرار لمدة ٧ أيام يحفظ بصمة SHA-256 لكل رسالة
  (مُرسِل+نص+وقت — لا يمكن عكسها للنص).
- كل ذلك يبقى على جهازك. النسخ الاحتياطي السحابي والنقل بين الأجهزة
  لقاعدة البيانات والإعدادات مُعطَّلان.

## فحص التحديثات (اختياري، مفعّل افتراضيًا)

إذا بقي خيار **Check for updates** مفعّلًا، يتصل التطبيق بـ `api.github.com`
(واجهة إصدارات GitHub العامة) **مرة واحدة على الأكثر كل ٢٤ ساعة** لمقارنة
أحدث إصدار منشور بالإصدار المثبَّت، ويعرض تنبيهًا برابط إن وُجد إصدار أحدث.
**لا يتم تنزيل أو تثبيت أي شيء تلقائيًا أبدًا.** هذا الطلب مجرد طلب GET مجهول
عبر HTTPS لا يتضمن أي معرّفات جهاز أو إعدادات أو بيانات مدفوعات. يمكنك
إيقافه من: الإعدادات → أمان المطابقة → *Check for updates*. أي خطأ (انقطاع
الشبكة أو تجاوز الحد) يُتجاهل بصمت.

## ما لا يُجمَع أبدًا

- لا تحليلات، لا تقارير أعطال، لا إعلانات، لا أدوات تتبع — لا يوجد أي منها
  في الكود.
- المطوّر لا يستلم **أي شيء**: لا تُجمع أي بيانات لأي مستخدم ولا لأجل أي
  مستخدم، أبدًا.

## كيف تحذف بياناتك

- من التطبيق: **لوحة التحكم → Clear** تمسح الرسائل المُحلَّلة وسجل الإرسال.
- الحذف الكامل: ألغِ تثبيت التطبيق؛ يمحى معه كل شيء (قواعد البيانات،
  الإعدادات المشفرة، المفاتيح المرتبطة بـ Keystore).
- رسائل الهاتف لا يعدّلها التطبيق ولا يمسحها أبدًا — يقرأ فقط رسائل
  المُرسِلين المسموح بهم.

## الأذونات المستخدمة

| الإذن | السبب |
|---|---|
| `RECEIVE_SMS` | استقبال رسائل المحافظ الواردة من مُرسِليك المسموح بهم (لا يقرأ التطبيق صندوق الرسائل نفسه) |
| `INTERNET` / `ACCESS_NETWORK_STATE` | التواصل مع سيرفرك ومعرفة حالة الاتصال |
| `FOREGROUND_SERVICE*` | إبقاء خدمة التحقق ٢٤/٧ حيّة |
| `RECEIVE_BOOT_COMPLETED` | إعادة تشغيل البوابة بعد إعادة تشغيل الهاتف |
| `POST_NOTIFICATIONS` | عرض حالة البوابة وتنبيه «لا تتحقق من المدفوعات» |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | استثناء اختياري من تحسين البطارية |
| `WAKE_LOCK` | إبقاء حلقة التحديث تعمل |
