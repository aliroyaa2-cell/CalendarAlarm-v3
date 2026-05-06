# CalendarAlarm v3.1

تطبيق Android شخصي يعمل كـ **قارئ تقويم + منبه شاشة كاملة قوي** لجهاز Samsung Galaxy S24 Ultra.

---

## الفكرة الجوهرية

1. يقرأ كل تقاويم الجوال (Google + Samsung + Outlook + إلخ).
2. تختار أي تقاويم تبغى تستوردها.
3. كل أحداث التقاويم المفعّلة تظهر في الشاشة الرئيسية.
4. عند وقت الحدث: **منبه شاشة كاملة قوي** فوق lock screen + صوت + اهتزاز.
5. خيارات: Snooze (5/10/30 دقيقة + قائمة متقدمة) + Dismiss + Edit (يفتح Google Calendar الأصلي).
6. مزامنة تلقائية (Foreground Service + ContentObserver).
7. يعمل بعد restart الجوال (BootReceiver).

---

## آلية المنبه (Phase 1 / Phase 2)

هذا اللي يميّز التطبيق ويضمن إن الشاشة تستيقظ فعلياً على Samsung One UI 8:

- **Phase 1** (أول 8 ثوان من المنبه — قابل للتعديل في الإعدادات):
  - الشاشة تستيقظ قسرياً عبر `SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP`
  - تظهر AlarmOverlayActivity فوق lock screen عبر `setShowWhenLocked(true) + setTurnScreenOn(true) + FLAG_KEEP_SCREEN_ON + FLAG_TURN_SCREEN_ON + FLAG_SHOW_WHEN_LOCKED + FLAG_DISMISS_KEYGUARD`
  - الصوت يطلق + الاهتزاز

- **Phase 2** (بعد Phase 1):
  - يُحرّر الـ wake-lock + يُمسح `FLAG_KEEP_SCREEN_ON`
  - الشاشة تطفي مع timeout الجوال الطبيعي (~30 ث) — ما تستهلك بطارية
  - الـ Activity تبقى في الذاكرة — لو فتحت الجوال بعد 5 دقائق، المنبه ما زال موجود
  - الصوت والاهتزاز يستمرّون حتى يضغط المستخدم Dismiss أو Snooze

🔹 لا نستخدم `PowerManager.ON_AFTER_RELEASE` أبداً (هذا الفلاج يضيف تأخير بعد الإطلاق وكان سبب مشاكل في محاولات سابقة).

🔹 نستخدم `AlarmManager.setAlarmClock()` (مش `setExact` ولا `setExactAndAllowWhileIdle`) — هي الطريقة الوحيدة اللي تتجاوز Doze Mode بثبات على One UI 8.

---

## كيف تبني الـ APK من GitHub (بدون Android Studio)

### الخطوات:

1. **أنشئ Repository جديد على GitHub** (private أفضل):
   - افتح [github.com/new](https://github.com/new)
   - اسم المشروع: `CalendarAlarm` (أو أي اسم)
   - اضغط **Create repository**

2. **ارفع المشروع كاملاً**:
   - من زرّ **Add file → Upload files**
   - اسحب كل ملفات المشروع المفكوكة من ZIP
   - اضغط **Commit changes**

3. **شغّل البناء**:
   - افتح تبويب **Actions** في الـ Repository
   - تحت "Build Debug APK" → اضغط **Run workflow**
   - انتظر ~5-7 دقائق

4. **حمّل الـ APK**:
   - بعد ما يخلّص (✓ خضراء)، افتح الـ run
   - تحت **Artifacts** → اضغط `CalendarAlarm-debug-apk`
   - يحمّل ZIP فيه `app-debug.apk`

5. **ثبّت على الجوال**:
   - فك الـ ZIP، انقل `app-debug.apk` إلى الجوال (USB أو Google Drive)
   - افتحه من File Manager → السماح بـ "Install unknown apps"
   - ثبّت

---

## الإعداد الأول بعد التثبيت (مهم)

افتح التطبيق → سيظهر بانر أحمر فوق "في صلاحيات ناقصة". اضغطه ليفتح **الإعدادات → الصلاحيات**، وامنح:

| الصلاحية | كيف |
|---|---|
| ✔️ قراءة التقويم | اضغط "منح" → اقبل |
| ✔️ عرض الإشعارات | اضغط "منح" → اقبل |
| ✔️ المنبه بشاشة كاملة | اضغط "فتح الإعدادات" → فعّل |
| ✔️ المنبهات الدقيقة | اضغط "فتح الإعدادات" → فعّل |
| ✔️ الاستثناء من Battery | اضغط "فتح الإعدادات" → اختر "السماح" |
| ✔️ الوصول للنغمات | اضغط "منح" → اقبل |

ثم في الإعدادات → اضغط **إعدادات Battery (Samsung)** → غيّر التطبيق إلى **Unrestricted**.

ثم ارجع لـ **إدارة المصادر** → اختر التقاويم اللي تبغاها → احفظ → اضغط "مزامنة الآن".

---

## الـ Stack

- Kotlin 2.0.21
- Android Gradle Plugin 8.7.3
- Gradle 8.9
- JDK 17
- compileSdk 35 (Android 14) — يعمل على Android 8.0+ (minSdk 26)
- Room 2.6.1 (KSP)
- WorkManager 2.9.1
- Coroutines 1.9.0
- Material Components 1.12.0

---

## الـ Package & Bundle ID

`com.alaimtiaz.calendaralarm` (وفي debug: `com.alaimtiaz.calendaralarm.debug`).

---

## الافتراضات

- اللون الرئيسي: Material Blue `#1976D2`
- اللغة: عربي أساسي
- مدة Phase 1 الافتراضية: 8 ثوان (قابلة للتعديل من 3 إلى 60)
- نافذة المزامنة: 90 يوماً للأمام
- المزامنة الدورية: كل 60 دقيقة + ContentObserver لحظي
- نغمة افتراضية واحدة لكل المنبهات (تُختار من الإعدادات)
- الأحداث المتكرّرة: تُخزّن موسّعة (instance per occurrence) — `externalId = "${eventId}_$begin"`

---

## ملاحظات تشخيصية

لو ما اشتغل المنبه بصورة صحيحة على Samsung S24 Ultra:

1. تأكد من **Battery → التطبيق → Unrestricted**
2. تأكد من **Settings → Apps → CalendarAlarm → Notifications → Allow**
3. تأكد من **Settings → Apps → Special access → Schedule exact alarms → Allow**
4. تأكد من **Settings → Apps → Special access → Display over other apps → Allow** (لو طلبه)
5. سجّل فيديو + `adb logcat -s "AlarmScheduler:*" "AlarmOverlay:*" "EventAlarmReceiver:*" "BootReceiver:*" "CalendarSyncService:*"` وأرسلها

---

## License

شخصي. لا يُنشر على Play Store. للاستخدام الذاتي فقط.
