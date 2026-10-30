# FinTrack: private, local-first personal finance tracker (Android)

Reads bank, UPI and card SMS **on the phone only**, builds a ledger, never double-counts money you move between your
own accounts, and produces day / week / month reports in a spreadsheet-style layout, plus a nightly spending notification.
**No internet permission, no cloud, no bank login.**

Status: working Android app (v0.1.0). Verified on an Android 35 emulator, not yet on a physical phone. See *What is verified* below.

## What it does

- **Reads SMS** from banks, card issuers and apps (sender names with letters). Chats with people (phone-number senders) are never opened.
- **Parses deterministic templates** kept as data in [parser-spec/](parser-spec/) (Kotak, CUB, Federal, TMB, HDFC salary, ICICI/SBI card, ICICI Life, Airtel Payments Bank notices and more). Unknown formats are kept for review, never dropped or guessed.
- **Ledger rules** (pure Kotlin in [core/](core/)): exact paise arithmetic; duplicate messages and the same transaction seen twice are counted once; transfers between your own accounts are matched by UPI reference and counted once, never as income or spending; a payment to CRED is a card-bill payment, not an expense.
- **Reports** for day, week and month: credit-card spends, bank spends, card bills due, lent, regular payments, EMI, salary flow, per-account movement, disclosures about incomplete data. Every figure comes from one report builder, so the notification equals the screen.
- **People payments**: under your threshold they are spending; above it you decide Lent or Spent. Lent is never chosen for you. Lent rows show which bank account sent it.
- **Notifications**: daily (night, default 22:00), weekly, monthly, optional daily limit alert. One per period, no amounts on the lock screen unless you turn that on.
- **Privacy**: encrypted database (SQLCipher, key protected by the Android Keystore), optional fingerprint/screen-lock gate, screenshots blocked by default, passphrase-encrypted backups, CSV export, erase-everything.

## Build and run

Everything needed lives in `.toolchain/` inside this folder (JDK 17, Gradle, Android SDK). Nothing is installed system-wide.

```powershell
. .\tools\dev-env.ps1                    # session-only environment
.\gradlew.bat :core:test                 # 102 JVM tests: parser, ledger, transfers, reports, notifications
node parser-spec/run.mjs                 # same parser fixtures through the language-neutral harness
.\gradlew.bat :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :app:connectedDebugAndroidTest   # on-device tests (needs an emulator or phone)
```

### Install on your phone

1. Enable *Developer options* and *USB debugging* on the phone, connect it, then `adb install -r app\build\outputs\apk\debug\app-debug.apk`
   (adb is in `.toolchain\android-sdk\platform-tools`), or copy the APK to the phone and open it.
2. Open FinTrack. On Android 13+ a sideloaded app may need **Settings > Apps > FinTrack > ⋮ > Allow restricted settings** before the SMS permission can be granted.
3. **Accounts** tab: add each bank account and card (bank key, and the digits the SMS shows, e.g. `2126`). **Settings**: add your name exactly as it appears in UPI messages (this is how your own transfers are recognised), allow SMS and notifications, then **Read SMS now**.
4. Open **Needs your attention** from Home to decide anything uncertain.

A release build for distribution must be signed with your own key; the one used in testing was a throwaway.

## What is verified (and what is not)

| Verified | How |
|---|---|
| Parser, dedup, transfer matching, card dues, reports, notification text | 102 JVM tests, including the PRD's salary-split scenario and the critical invariants |
| Encrypted database, Keystore key, restart persistence, encrypted backup round-trip, wrong-passphrase rejection | on-device test, Android 35 emulator |
| Live SMS while the app is in the background, duplicate across two senders merged, phone-number sender ignored | emulator `sms send`, checked on screen |
| Notification content, one per period, amount-free lock-screen version | on-device test |
| Home and Reports screens render correctly with real data | emulator screenshots |
| Minified release build starts and processes SMS without errors | emulator, no crash or class-not-found in the log |

**Not verified:** a physical phone and your real bank messages (parsers cover only the formats seen so far); the other screens' interactions (Settings, account and regular-item dialogs, backup/restore UI, biometric prompt); the notification actually firing at the scheduled time (WorkManager timing, battery optimisation on some phones).

## Known limitations

- Templates exist only for message formats supplied so far. Other banks' messages appear under *not understood*; add a template to `parser-spec/templates.json` plus a sanitised fixture.
- No templates yet for **failed transactions, refunds, reversals** or a card's **payment-received** confirmation (no samples). Refunds can be entered manually; card payments assigned from a bank stay labelled *awaiting issuer confirmation*.
- Transfer matching is one-to-one. Split transfers and transfers with fees are not matched automatically.
- Persistence stores each ledger entry as a JSON payload in SQLCipher rather than the fully normalised schema in the design docs; schema version 1, no migrations yet.
- CSV export only. The Excel workbook in `samples/` is a design mock with fake data.
- Person-vs-business detection is a name heuristic; it only chooses a default, you can always change it.

## Privacy rules for contributors

- Never commit real SMS, names, account numbers, UPI references or balances. Keep them in `private/` (git-ignored). Fixtures must be sanitised.
- **Before making this repository public:** the fixtures and docs reuse message *shapes* from real messages, including real last-4 account digits and some real amounts. Replace those with fake values first.

## Layout

`core/` pure Kotlin ledger engine and parser · `app/` Android app (Compose, SQLCipher, WorkManager) · `parser-spec/` SMS templates and fixtures shared by Kotlin and Node · `docs/` design documents · `samples/` fake-data Excel mock · `tools/` environment script

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).
