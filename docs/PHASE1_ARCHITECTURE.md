# Personal Finance Tracker — Phase 1: Requirements & Architecture

Status: design only. No application code yet. See §11 for the toolchain blocker and the open questions that change the design.

---

## 0. The target report (from your spreadsheet) — treated as the spec

Your screenshot is the "final report". The app must produce this exact layout, with every amount **and every reason cell** filled from SMS.

| Report block | Columns | Source in the app |
|---|---|---|
| Credit Card Spends | Spends on CC · CC Name with reason | Debit/purchase SMS from a credit-card account |
| Bank Spends | Spends on Bank · Bank Name with reason | Debit SMS from a bank account (UPI, debit card, IMPS…) classified as an external expense |
| CC Billed Tomorrow (red) | Amount · CC Name with reason | Credit-card statement/bill SMS whose due date is tomorrow |
| Lent | Lent · Upi_id | Outgoing UPI payments to a person that you tag as "lent" |
| Monthly Regular Spends | Amount · Spends reason | Recurring templates (SIP, rent, insurance…) matched to SMS |
| EMI on CC | EMI On CC · EMI Reason/Months | EMI SMS from a credit card (e.g. `TV 1/12`) |
| Total row | one total per amount column | Computed by the calculation engine (§6), never typed |

### 0.1 How the "reason" columns get filled from SMS

An SMS rarely contains a *purpose*. It contains a **merchant, a payee or a free-text narration**. The reason is therefore derived in a fixed priority order, and **nothing is invented**:

1. **Your override** for this transaction (set once, then remembered).
2. **Learned payee rule**: you labelled this merchant/VPA before, so reuse that label.
3. **Merchant or narration from the SMS**: `at SWIGGY`, `towards SALARY`, `Info: NETFLIX`.
4. **Payee name / VPA from the SMS**: `to RAMESH K`, `to paytmqr12@ptys`.
5. **Blank, flagged "needs reason"**. It is counted on the dashboard and never guessed.

Display format of the "CC/Bank name with reason" cell: `<account label> ••<last4> · <reason>`, e.g. `HDFC CC ••1234 · SWIGGY`. The raw SMS stays linked, so tapping a cell shows the source message.

Examples (illustrative; real templates come from your actual SMS, see Q1):

| SMS (shape) | Block | Amount | Reason cell |
|---|---|---|---|
| `Rs.1499.00 spent on HDFC Bank Card x1234 at SWIGGY on 28-10-26` | CC Spends | ₹1,499 | `HDFC CC ••1234 · SWIGGY` |
| `Sent Rs.51.00 from A/c *5678 to paytmqr12@ptys, UPI Ref 4412…` | Bank Spends | ₹51 | `HDFC ••5678 · paytmqr12@ptys` |
| `Rs.1500 sent to rahul@oksbi, UPI Ref …` + you tap "Lent" | Lent | ₹1,500 | UPI id = `rahul@oksbi` |
| `Total due Rs.568.18 on card x4321, due 30-Oct-26` (today = 29-Oct) | CC Billed Tomorrow | ₹568.18 | `ICICI CC ••4321 · bill due` |
| `EMI of Rs.5000 … TV … 1 of 12 debited on card x1234` | EMI | ₹5,000 | `TV 1/12` |

### 0.2 Observations about your sheet that I need you to confirm (they affect totals)

1. **Row 7 of Bank Spends (₹1,500, green) equals Lent (₹1,500, green).** Your Bank Spends total (₹3,206) *includes* it: 900+51+210+240+120+185+**1,500** = 3,206. So the same money appears in two blocks. Under the PRD's rules, money you lent is not an expense. **Proposed:** Bank Spends excludes lent amounts (₹1,706) and Lent shows ₹1,500; the green highlight links the two. Say if you want your original double-showing instead (Q3).
2. **Total row mixes different things.** CC Billed Tomorrow (₹2,067) is a *liability* for purchases already in CC Spends, so it must not be added into "total spent". **Proposed:** keep one total per column exactly like your sheet, and add a separate "Total spent" line = CC Spends + Bank Spends (ex-lent) + Monthly Regular + EMI.
3. **Monthly Regular Spends look like expected amounts** (SIP 8,000, Rent 1,400, college fee 20,000). **Proposed:** each is a recurring template with an expected amount; when a matching SMS arrives, the SMS amount replaces it and the row is marked "seen"; otherwise it shows as "expected, no SMS yet".
4. **Red = payable tomorrow, green = lent / cross-linked.** I'll keep those semantics and add a legend, with colour-independent icons for accessibility.

---

## 1. Product requirements (concise)

**Goal.** Local-first Android app that reads bank/UPI/card SMS on the device, builds a reliable ledger, and produces the report in §0 plus daily/weekly/monthly reports, with no double counting across the user's own accounts.

**Must have (MVP):** SMS ingestion (live + inbox backfill) · deterministic parsers per bank · multi-account · internal-transfer matching · dedup · ledger · your report layout · correction UI with audit trail · local notifications · coverage warnings · encrypted local storage · export/backup.
**Not in MVP:** iOS inbox reading, cloud sync, bank login, ML parsing, multi-user.
**Hard rules:** money = integer minor units (paise) · reports derived from the ledger only · uncertain → kept visible, excluded from confirmed totals · no silent discard · no credentials/PIN/OTP stored · raw SMS never leaves the device.

## 2. Technology stack

| Concern | Choice | Why |
|---|---|---|
| Platform (MVP) | **Native Android, Kotlin** | SMS inbox access, `SMS_RECEIVED` receivers and WorkManager are first-class; cross-platform wrappers add plugins in exactly the riskiest area. |
| UI | Jetpack Compose + Material 3 | Fast to build tables/charts, state-driven. |
| Core logic | **Pure-Kotlin JVM module `:core`** (no Android imports) | Parser, dedup, transfer matcher, calculation engine are unit-testable on a laptop without an emulator; later shareable with iOS via Kotlin Multiplatform. |
| Database | **Room (SQLite)**, encrypted with SQLCipher | Relational, transactional, migrations, exact-integer arithmetic; ledger needs constraints and joins. |
| Money | `Long` paise + ISO currency code | No floating point anywhere in finance code. |
| Time | `java.time`, instants stored as UTC epoch millis, user time zone for boundaries | Correct midnight/month-end bucketing. |
| Background | WorkManager (backfill, summaries), `BroadcastReceiver` (live SMS), AlarmManager/WorkManager for notifications | Survives restarts. |
| Keys | Android Keystore wraps the DB key; optional biometric gate | No plaintext key on disk. |
| Tests | JUnit5 + Kotest for `:core`, Room in-memory + Robolectric for `:data`, Compose UI tests | Covers the 20 scenarios in the PRD. |
| Export | CSV + **XLSX in the §0 layout** | You already work in this sheet format. |

Rejected: Flutter/React Native (SMS and background plumbing through plugins, no benefit for an Android-first MVP); cloud-first (violates local-first/privacy goal).

## 3. Architecture and data flow

```
 ┌──────────────┐  ┌───────────────────┐
 │ SMS receiver │  │ Inbox backfill    │   (Android layer, :app)
 │ (live)       │  │ (ContentResolver) │
 └──────┬───────┘  └─────────┬─────────┘
        └──────────┬─────────┘
                   ▼
        ┌─────────────────────┐   raw_message (append-only, hashed, dedup by hash)
        │ 1. Ingestion        │──────────────────────────────────────────────┐
        └─────────┬───────────┘                                              │
                  ▼                                                          │
        ┌─────────────────────┐   classify: txn / OTP / promo / balance /    │
        │ 2. Parser           │   failed / pending / reversal / EMI / bill   │
        │  (bank templates)   │──▶ ParsedEvent + field confidences           │
        └─────────┬───────────┘                                              │
                  ▼                                                          │
        ┌─────────────────────┐   merchant/VPA clean-up, reason derivation   │
        │ 3. Normalizer       │   (§0.1), account resolution by last4+sender │
        └─────────┬───────────┘                                              │
                  ▼                                                          │
        ┌─────────────────────┐   identity key, same-event merge             │
        │ 4. Deduplicator     │──▶ audit_log                                 │
        └─────────┬───────────┘                                              │
                  ▼                                                          │
        ┌─────────────────────┐   debit↔credit pairing → transfer_group      │
        │ 5. Transfer matcher │──▶ reconciliation_event                      │
        └─────────┬───────────┘                                              │
                  ▼                                                          ▼
        ┌─────────────────────────────────────────────────────────────────────────┐
        │ 6. LEDGER (canonical, Room/SQLCipher): transaction, account, transfer…  │
        └─────────┬───────────────────────────────────────────────────────────────┘
                  ▼
        ┌─────────────────────┐    single source for numbers
        │ 7. Calc engine      │──▶ Report builders (§0 report, daily/weekly/monthly)
        └─────────┬───────────┘
          ┌───────┴────────┬──────────────┐
          ▼                ▼              ▼
     Compose UI      Notifications   Export / Backup
```

Rules: every arrow is idempotent (re-running yields identical ledger and totals); stages 2–5 are pure functions in `:core`; the UI and notifications call the same calc engine, so the notification number equals the dashboard number.

Modules: `:core` (parser, normalizer, dedup, transfer, calc) · `:data` (Room, DAOs, migrations, repositories) · `:app` (SMS, WorkManager, notifications, Compose UI, security).

## 4. Database schema (proposal)

All amounts are `INTEGER` paise. All timestamps are UTC epoch millis. Foreign keys enforced.

```
user(id, name, timezone, currency_default)
institution(id, name, kind[BANK|CARD_ISSUER|WALLET|UPI_APP], sms_sender_patterns)
account(id, user_id, institution_id, label, kind[SAVINGS|CURRENT|CREDIT_CARD|WALLET|CASH],
        last4, upi_ids, status[ACTIVE|CLOSED], opening_balance, opening_balance_at, currency)
message_source(id, sender_address, institution_id, trust_level)
raw_message(id, source_id, body_encrypted, body_hash UNIQUE, received_at, tx_time_hint, parsed_status, parser_version)
merchant_payee(id, canonical_name, vpa, kind[MERCHANT|PERSON], default_category_id, default_reason)
category(id, name, parent_id, kind[EXPENSE|INCOME|TRANSFER|LIABILITY|INVESTMENT])
transaction(id, account_id, occurred_at, posted_at, amount, currency, direction[DEBIT|CREDIT],
            type[PURCHASE|INCOME|TRANSFER|REFUND|REVERSAL|FEE|CC_BILL_PAYMENT|CASH_WITHDRAWAL|
                 INVESTMENT|LOAN|EMI|LENT|LENT_REPAYMENT|UNKNOWN],
            status[SUCCESS|PENDING|FAILED|REVERSED], payment_method, merchant_id,
            reason_text, reason_source[USER|LEARNED|SMS_MERCHANT|SMS_PAYEE|NONE],
            report_block[CC_SPEND|BANK_SPEND|REGULAR|EMI|LENT|NONE],
            category_id, extraction_confidence, reconciliation_status[CONFIRMED|NEEDS_REVIEW|POTENTIAL_TRANSFER],
            dedup_key, transfer_group_id, supersedes_id, source_message_id, created_at, updated_at)
transaction_identifier(id, transaction_id, kind[UTR|UPI_REF|IMPS|BANK_REF|AUTH_CODE], value,
                       UNIQUE(kind, value, account_id))
transfer_group(id, kind[ONE_TO_ONE|ONE_TO_MANY|MANY_TO_ONE], confidence, state[AUTO|USER_CONFIRMED|REJECTED], fee_amount)
recurring_template(id, name, expected_amount, cadence, payee_match, account_id, block[REGULAR|EMI],
                   emi_total_months, emi_start)
emi_plan(id, card_account_id, reason, installment_amount, total_months, paid_count, source_message_id)
statement(id, card_account_id, total_due, min_due, due_date, statement_date, source_message_id)
lend_record(id, transaction_id, upi_id, state[OUTSTANDING|PARTIAL|SETTLED], settled_by_transaction_id)
balance_snapshot(id, account_id, balance, as_of, source[SMS|USER])
daily_summary(date, ...)            -- DERIVED CACHE, rebuildable, never authoritative
notification_pref(id, kind, time, enabled, hide_amounts_on_lock, threshold)
notification_log(id, kind, period_key UNIQUE, sent_at)   -- prevents duplicates
reconciliation_event(id, kind, transaction_ids, decision, decided_by[AUTO|USER], at)
audit_log(id, entity, entity_id, field, old, new, actor, at, reason)
```

Raw → normalized → derived stay separate: `raw_message` is append-only; `transaction` is rebuildable from it plus user overrides; `daily_summary` is a disposable cache.

## 5. Deduplication and transfer-matching rules

### 5.1 Transaction identity (strongest first)
1. `(account, UTR | UPI ref | IMPS ref | bank ref)` equal → **same event** (merge, keep richest fields, link both raw messages).
2. Same account + direction + amount + status and `|Δt| ≤ 2 min` **and** same merchant/VPA **and** no conflicting identifier → *probable duplicate*: merge only if both messages come from different sources (e.g. bank SMS + UPI-app SMS); otherwise flag for review.
3. Equal amount/time alone → **never** a duplicate. Two ₹120 chai payments stay two.
4. Identical `raw_message.body_hash` → ignore silently (reprocessing), logged.
5. Balance alerts and OTP/promo messages never create transactions.
6. Failed attempt + later success: different identifiers or status → two records; the failed one is excluded from totals but kept.
7. Refund/reversal: its own record, type `REFUND`/`REVERSAL`, linked to the original by reference or merchant+amount; never merged into the purchase.
8. Corrections (e.g. status update for the same reference) create a `supersedes_id` chain; the old row stays for audit.
9. Every merge/split/override writes `audit_log` + `reconciliation_event`.

### 5.2 Internal transfer matching
Candidates: one DEBIT on account X and one CREDIT on account Y, both owned by the same user, X≠Y, same currency.

| Signal | Score |
|---|---|
| Same UTR/IMPS/UPI ref on both sides | +100 (auto-link) |
| Counterparty account last4/VPA in debit's text matches Y (or vice versa) | +40 |
| Amount equal (or debit = credit + known fee) | +20 |
| `|Δt|` ≤ 10 min / ≤ 24 h (configurable) | +20 / +5 |
| Narration says "self"/own name | +10 |
| Another candidate with the same score exists | −30 (ambiguity) |

- ≥ 90 → auto-link, `state=AUTO`. 50–89 → `NEEDS_REVIEW`, ask you. < 50 → no link.
- **Amount alone scores 20 and can never reach the threshold.**
- Fees: debit − credit equals a known fee pattern → fee becomes a separate `FEE` expense, the rest is the transfer.
- One-to-many / many-to-one: group candidates summing exactly to the other side within the window; confirm with you unless every leg has a shared reference.
- Debit to unknown destination: `POTENTIAL_TRANSFER`, excluded from confirmed expense, shown under "Needs review" with the amount disclosed.
- Delayed or missing counterpart: stays `POTENTIAL_TRANSFER`, re-evaluated whenever new messages arrive; a missing message is never treated as "no transaction".

## 6. Financial calculation definitions

Sign convention: ledger stores positive amounts with a direction. **External expense** = confirmed `PURCHASE`/`FEE`/regular/EMI debits. All report numbers are `SUM` over the ledger for `[start, end)` in the user's time zone.

| Event | Income | Expense | Internal transfer | Notes |
|---|---|---|---|---|
| Salary/other credit | ✔ | | | |
| Linked transfer pair | | | ✔ (once, reported separately) | Both legs remain in account histories |
| Card purchase | | ✔ (CC Spends) | | Counted when charged |
| Card bill payment from bank | | | | Liability settlement, not expense (avoids double count) |
| Refund | | reduces expense in the original period/category | | Net, shown on its own line |
| Reversal | | cancels the original | | |
| Cash withdrawal | | | | Cash-in-hand movement; spending needs manual entry, flagged |
| Investment (SIP) | | not an "expense" in net cash flow; shown in Monthly Regular as in your sheet | | Report flags it as investment |
| Loan disbursed | | | | Liability, not income |
| Lent | | | | Receivable, outstanding balance shown; repayment settles it |
| Pending / failed | | | | Excluded from totals, counted in "unconfirmed" |

Key formulas:
- `ExternalExpense = Σ confirmed PURCHASE + FEE + EMI + regular debits − refunds`
- `Income = Σ confirmed INCOME credits` (excludes transfer legs, refunds, loan disbursals, lent repayments)
- `NetExternalCashFlow = Income − ExternalExpense`
- Coverage banner: shown when any active account has no message for > N days, a balance snapshot disagrees with the ledger, or SMS permission is off.
- Balance reconciliation: `opening + Σcredits − Σdebits` vs latest SMS balance snapshot; the difference is reported as "unexplained", never absorbed.

## 7. Android and iOS limitations

- **Android:** reading the SMS inbox needs `READ_SMS`/`RECEIVE_SMS`, which are *restricted permissions* under Google Play policy. As I recall, expense-tracker apps are generally not among the permitted exceptions, so a Play Store release is likely blocked. **Verify the current policy before choosing distribution.** The realistic path for a personal app is sideloaded APK (you grant the permission yourself). Newer Android versions also restrict sideloaded apps' access to restricted permissions behind an extra settings toggle.
- **iOS:** apps cannot read the SMS inbox. Alternatives: statement import (PDF/CSV), manual entry, share-sheet of a message into the app, Shortcuts automation. Out of scope for MVP; `:core` is kept portable for this.
- **Battery/background:** OEM task killers can delay live SMS handling, which is why the inbox backfill on app open is mandatory, with coverage warnings.

## 8. Security and privacy risks

| Risk | Mitigation |
|---|---|
| SMS bodies contain account numbers, balances | Encrypted DB (SQLCipher, key in Keystore); store only fields needed + hashed raw body, or encrypted body for reprocessing |
| Notifications leaking amounts on lock screen | `hide_amounts_on_lock` default **on**, public-version notification redacted |
| Logs leaking data | Logging wrapper strips amounts/numbers; no SMS text in logs |
| Backup exposure | Backup encrypted with user passphrase; export warns about plaintext CSV/XLSX |
| Over-broad permission | Only `READ_SMS`/`RECEIVE_SMS` + `POST_NOTIFICATIONS`; **no INTERNET permission in MVP**, so no data can leave the device |
| AI/cloud parsing | Not used in MVP; any future use is opt-in with explicit consent |
| App access | Optional biometric lock; screens excluded from recents |
| Credentials | Never requested or stored |

No regulatory compliance is claimed (DPDP etc.); that needs a separate review of the real implementation.

## 9. MVP plan and milestones

| # | Milestone | Done when |
|---|---|---|
| M0 | Toolchain + project skeleton (`:core`, `:data`, `:app`) | `gradle test` green on an empty test |
| M1 | Schema, migrations, money/time utilities | Round-trip tests, migration tests |
| M2 | Parser v1 for **your** banks/cards + message classifier + reason derivation | Golden-file tests on your sanitized SMS samples |
| M3 | Normalizer, account resolution, dedup | Dedup test suite (exact, near, delayed, refund, reversal, identical-legit) |
| M4 | Transfer matcher | Salary-split scenario (₹80k → 20k/15k/10k) passes |
| M5 | Calc engine + **your report layout** (§0), with your sheet's numbers as a fixture | Reproduces the 11,254 / 3,206 / 2,067.18 / 1,500 / 37,350 / 5,000 totals |
| M6 | Compose UI: dashboard, accounts, transactions, review queue | Manual run on device |
| M7 | Daily/weekly/monthly reports, notifications | Dashboard = notification numbers |
| M8 | Encryption, backup/restore, export (CSV + XLSX in your layout), biometric lock | Restore reproduces identical totals |
| M9 | Full invariant/regression pass, release prep | All 20 scenarios + invariants green |

## 10. Critical invariants (each becomes an automated test)
Reprocessing a message leaves totals unchanged · rebuilding a report gives identical results · transfers never inflate income/expense · matched transfers stay in both account histories · refunds are never "duplicates" · uncertain items are never dropped · missing messages ≠ no transaction.

## 11. Blockers and open questions

> Update: questions 1, 4, 5, 7 are answered and the decisions are in [PHASE2_SAMPLE_ANALYSIS_AND_RULES.md](PHASE2_SAMPLE_ANALYSIS_AND_RULES.md). Distribution is personal now and F-Droid later, so the Play Store SMS restriction in §7 does not block the MVP.

**Toolchain blocker.** This machine has Node and Python only. There is no JDK, Gradle, Android SDK or Android Studio, so I cannot compile or run Kotlin here. Install **Android Studio** (it bundles the JDK and SDK) before M0. Until then I won't write code I cannot test.

**Questions that change the design:**
1. **Sample SMS.** Please share 3–5 sanitized real messages (mask digits/names) per bank and card you use. Parsers are deterministic templates; I won't guess formats.
2. Which banks, cards and UPI apps are in scope, and how many accounts are yours?
3. Should **lent money be excluded** from Bank Spends (my proposal) or shown in both, as in your sheet (§0.2-1)?
4. **"CC Billed Tomorrow"**: do you mean the statement is *generated* tomorrow, or the payment is *due* tomorrow? I assumed due date.
5. Which **period** does the report cover: one month, or selectable day/week/month? (Monthly Regular suggests a month.)
6. **Monthly Regular / EMI**: do you want to define these yourself (recommended), or have the app auto-detect recurring payments too?
7. Distribution: personal sideload only, or planning Play Store? This decides whether SMS reading is viable at all (§7).
8. Do you want the XLSX export to replicate your sheet's formatting (colours, totals row)?
