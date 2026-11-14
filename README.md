# KDP Sales Notifier

Kotlin Android app (minSdk 26). Stores your KDP login encrypted, checks the KDP
reports every 6 hours in the background (WorkManager) and posts a notification only
when there are new sales.

## Build
1. Open this folder in Android Studio (Koala or newer, JDK 17) and let Gradle sync.
2. Run on a device/emulator, or Build > Build APK(s).

## Behavior
- First successful check = baseline, no notification (otherwise every sale already
  in this month's report would be announced).
- Notification lists each new sale: title | format | marketplace | royalty (USD),
  and the last line is the total royalties for the current month in USD.
- Non-USD royalties are converted with ECB rates (api.frankfurter.app).
- Sound + vibration scale with the number of new sales: 1 sale = 15% volume,
  2 = 30%, 3 = 45% ... 7 or more = 100% (of the phone's notification volume).
  They are played by the app, because a notification channel has one fixed volume.
- If login/report reading fails you get ONE quiet "KDP check failed" notice
  (not repeated every 6 hours).
- Android may delay background work a little (battery saver / Doze), so "every 6 hours"
  is approximate. Exempting the app from battery optimization makes it more punctual.

## Verification code (OTP)
When you tap "Save & start checking" (or whenever the saved session expires) Amazon may
send a one-time code. A background job cannot type it, so:
1. The app posts a "KDP needs your verification code" notification (once, not repeated;
   background checks pause until you answer, so Amazon does not send more codes).
2. Tap it, type the code in the app and press "Submit code".
3. The code is sent with "don't ask again on this device" ticked and the login session is
   stored encrypted, so later checks reuse it and normally need no code. If Amazon
   eventually asks again, you get the same notification.
- Wrong/expired code: press "Check now" to start a fresh login and get a new code.
- Captcha or "approve on another device" prompts are still not supported.

## Must-read limitations
KDP has no official API, so two things are unofficial:
1. Login scrapes Amazon's sign-in page. Captcha or 2-step verification cannot be
   completed in the background; the app then shows the "check failed" notice.
2. `KdpClient.REPORT_URL` and `parse()` are a best guess. Open kdpreports.amazon.com
   in desktop Chrome > DevTools > Network, find the request that returns the
   month-to-date sales rows, and put its URL in `REPORT_URL`. If its JSON field names
   differ, adjust the names in `parse()`.
Amazon can change either at any time, and automated access may conflict with Amazon's
terms of use; use at your own risk.
