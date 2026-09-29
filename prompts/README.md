# 🤖 جاهز تربط التطبيق بسيرفرك؟ — Ready-made backend prompts

<div align="center">

**[🇬🇧 English below](#english)** · دليل سريع بالعربي

</div>

---

## ⚠️ تحذير أمني — اقرأ قبل ما تستخدم ده بفلوس حقيقية

السيرفر اللي بيتبني بالذكاء الاصطناعي هو **بداية مش نظام إنتاج**. قبل ما يلمس
فلوس حقيقية **لازم** حد مختص يراجعه أمنيًا. راجع على الأقل: التحقق من الـ
secret والـ HMAC بثوابت زمنية، منع التكرار عبر `Idempotency-Key`، مراجع
معاملات فريدة، تحديد المعدل (rate limiting)، HTTPS فقط، الأسرار في متغيرات
بيئة، وعدم الوثوق بالمبالغ اللي العميل/التطبيق يبعتها. القائمة الكاملة في
[`../BACKEND_API_CONTRACT.md`](../BACKEND_API_CONTRACT.md) وضمن كل برومبت.

---

## ⚠️ Security warning — read before using this with real money

An AI-generated backend is a **starting point, not a production system**.
Before it ever touches real money, a competent human MUST security-review it.
At minimum verify: constant-time secret + HMAC checks, `Idempotency-Key`
dedupe, unique transaction references, rate limiting, HTTPS only, secrets in
env vars, and never trusting client-supplied amounts. The full checklist is
in [`../BACKEND_API_CONTRACT.md`](../BACKEND_API_CONTRACT.md) and inside
every prompt file.

---

## بالعربي — إيه المجلد ده؟

تطبيق **PaySync Gateway** محتاج **سيرفر صغير (backend)** فيه نقطتين (endpoints)
بس عشان يشتغل. المجلد ده فيه **برومبتات جاهزة** — انسخها والصقها في
ChatGPT / Claude / Gemini / Copilot وهيتبني لك السيرفر كامل، جاهز تشغّله وتربطه
بالتطبيق في دقايق.

### اختار البرومبت المناسب ليك

| الملف | بيبنيلك إيه |
|---|---|
| [`telegram-bot-prompt.md`](telegram-bot-prompt.md) | **بوت تليجرام كامل** — متجر بيع شحن/رصيد، العميل يدفع بالمحفظة والبوت يؤكد تلقائي |
| [`website-backend-prompt.md`](website-backend-prompt.md) | **موقع شحن** — صفحة طلب، العميل يحوّل، والطلب يتأكد أوتوماتيك لما الـ SMS يوصل |
| [`generic-backend-prompt.md`](generic-backend-prompt.md) | النقطتين بس — لو عندك موقع/بوت جاهز وعايز تضيفلهم الـ API |

### خطوات الاستخدام (٣ دقائق)

1. **افتح** ملف البرومبت اللي يناسبك وانسخه كله.
2. **الصقه** في أي مساعد ذكاء اصطناعي (Claude / ChatGPT / Gemini…).
   - البرومبتات بالإنجليزي عشان أعلى جودة ناتج — تقدر تطلب من المساعد يشرحهالك بالعربي بعد كده.
3. المساعد هيدّيك كود السيرفر → **شغّله** على جهازك أو على أي استضافة
   (Render / Railway / VPS…). هتلاقي تعليمات التشغيل في نهاية الكود.
4. هتحصل على حاجتين:
   - **رابط السيرفر** (مثال: `https://my-bot.onrender.com`)
   - **السر (secret)** الموجود في ملف `.env`

### ربط السيرفر بالتطبيق (داخل التطبيق)

1. افتح تطبيق PaySync Gateway → تاب **Settings**.
2. في خانة **Bot API URL**: اكتب رابط السيرفر (من غير `/` في الآخر).
3. في خانة **Webhook Secret**: الصق نفس الـ secret اللي في الـ `.env` بتاع السيرفر.
4. في **Allowed senders**: ضيف اسم المُرسِل زي ما بيظهر في الـ SMS — بالظبط.
   أمثلة: `VF-Cash` لفودافون كاش، `BanK-AlAhly` للتحويلات اللحظية (إنستاباي).
   المطابقة بالحروف بالظبط (مش حساسة للحروف الكبيرة/الصغيرة).
5. اضغط **Save** → ارجع لـ **Dashboard** وشغّل الزرار **ON**.
6. اسمح للتطبيق باستثناء **توفير البطارية** لما يسألك — ده ضروري عشان يشتغل 24/7.
7. جرّب: اعمل طلب من البوت/الموقع → حوّل المبلغ بالمحفظة → خلال ثواني
   هتلاقي الطلب اتأكد لوحده في الـ Live Log.

### تجربة محلية (من غير استضافة)

شغّل السيرفر على الكمبيوتر، وهات الـ IP بتاعك على الواي فاي
(`ipconfig` في ويندوز)، واكتب في التطبيق:
`http://192.168.x.x:8000` — التطبيق بيسمح بـ HTTP العادي للتجربة المحلية.
للإنتاج استخدم رابط HTTPS.

### ⚠️ ملاحظات أمان

- الـ **secret** هو مفتاحك — متبعتوش لأي حد ومتحطوش في كود عام.
- استخدم **HTTPS** في الإنتاج (الاستضافات المذكورة بتدي HTTPS جاهز).
- السيرفر لازم يتحقق من الـ secret والـ HMAC — البرومبتات بتطلب ده تلقائي.

---

## English

The app needs a tiny backend exposing exactly **2 endpoints**. This folder
contains copy-paste prompts that make any AI assistant build that backend for
you.

| Prompt | What it builds |
|---|---|
| [`telegram-bot-prompt.md`](telegram-bot-prompt.md) | A complete **Telegram store bot** with wallet top-ups |
| [`website-backend-prompt.md`](website-backend-prompt.md) | A **top-up website** whose orders auto-confirm from the wallet SMS |
| [`generic-backend-prompt.md`](generic-backend-prompt.md) | Just the **2 API endpoints**, to bolt onto an existing project |

**Workflow:** copy a prompt → paste into your AI assistant → run the generated
backend → note its public URL and the `GATEWAY_SECRET` from its `.env` →
open the app → **Settings** → enter the **Bot API URL** and **Webhook Secret**
→ add your wallet **allowed senders** (exact sender IDs as they appear in the
SMS, e.g. `VF-Cash`) → **Save** → toggle **ON** on the Dashboard.

Local testing: run the backend on your PC and use `http://<pc-ip>:8000`
(cleartext is allowed for local development). Use HTTPS in production and keep
the secret private.

The authoritative API specification both sides implement is
[`../BACKEND_API_CONTRACT.md`](../BACKEND_API_CONTRACT.md).
