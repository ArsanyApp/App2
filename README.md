# SMS Transactions (Android)

A small personal Android app that reads bank SMS messages **on your phone**, extracts the
transactions, shows them in a table, totals them by type, and exports them to a CSV file
that opens in Excel.

* No internet permission, no login, no cloud, no ads, no analytics.
* The only permission is **READ_SMS**. The app asks for it only when you tap a button that needs it.
* It never reads messages in the background. It only reads when you tap **Import SMS**.

---

## 1. Get the APK

The APK is built automatically by GitHub every time code is pushed.

1. Open the repository on GitHub, then tap the **Actions** tab.
2. Open the newest **Build APK** run that has a green tick ✅.
3. Scroll to **Artifacts** and download **sms-transactions-debug-apk** (a `.zip` file).
4. Unzip it. Inside is **`app-debug.apk`**.

Building it yourself (optional, needs Android Studio or the Android SDK):

```
./gradlew :core:test :app:assembleDebug
```

The APK is then at `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Install on your phone

1. Copy `app-debug.apk` to the phone (USB cable, Google Drive, or download it directly on the phone).
2. Open it from the **Files** / **Downloads** app.
3. Android will say installing unknown apps is blocked. Tap **Settings** and turn on
   **Allow from this source** for the app you used to open the file (e.g. Files or Chrome). Go back.
4. Tap **Install**. If Play Protect warns about an unknown app, tap **More details → Install anyway**
   (this happens with every personal app that is not from the Play Store).
5. Open **SMS Transactions**.

## 3. Use the app

**Select sender(s)**
1. Tap **Choose sender(s)**. The first time, Android asks for permission to read SMS – tap **Allow**.
2. You will see every sender name/number in your inbox with its message count
   (for example the National Bank of Egypt sender ID). Use the search box if the list is long.
3. Tick one sender, several senders, or **All Senders**, then tap **Done**. You can change this any time.

If you deny the permission, use **Paste SMS manually**: copy the messages, paste them
(empty line between messages) and type a sender name.

**Import existing SMS**
* Tap **Import SMS**. Messages from the selected sender(s) are read and transactions are saved
  on the phone. Running it again is safe – messages already imported are skipped (no duplicates).

**Choose dates**
* **Today** – only today.
* **This month** – 1st to last day of the current month.
* **Single day…** – pick e.g. the 24th; start and end become that day.
* Or tap **Start date** and **End date** for any custom range.
* Then tap **Show Transactions**. The table and the totals are recalculated every time you press it.

**Dates without a year**: some bank SMS say only `09-24` (no year). The app does not guess the
year. These are listed in a ⚠ box and are left out of the table/totals until you confirm them:
tap one to type the date, or tap **Use SMS received year** to take the year from the date the
SMS arrived on the phone.

**Read the totals**
* The **Totals by type** box shows, for each type (incoming transfer, outgoing transfer,
  card purchase, cash withdrawal, deposit, bank fee, refund, other/unknown), the number of
  transactions and the total amount, per currency.
* **Total incoming transfers** and **Total outgoing** are shown separately (they are not a balance
  or profit). Available balances are never added to totals; different currencies are never mixed.
* Tap the box title to hide/show it.

**Check or correct a transaction**
* Rows marked ⚠ need review. Tap any row to see the original SMS and correct any field, change
  the type, tick “I checked this”, or delete it.

**Export to Excel**
* Tap **Export CSV / Excel**, choose where to save (e.g. Downloads or Google Drive), tap **Save**.
* The file contains the same rows as the table (current senders + dates), followed by a summary
  (senders, date range, count, totals per type and currency). It is UTF-8 with BOM so Arabic
  shows correctly when opened in Excel.

---

## Project layout

* `core/` – plain Kotlin: SMS parser, date filter, totals (exact `BigDecimal`), CSV export, and tests.
* `app/` – the Android app (Kotlin, Jetpack Compose, Room database).
