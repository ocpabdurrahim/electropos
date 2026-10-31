# ElectroPOS

A point-of-sale and inventory app for mobile phone and electronics shops, as an Android app and a web app that share the same shop. Sell at the counter, buy from suppliers, track every IMEI and serial number, take returns, keep customer and supplier dues, expenses and the cash book, and see profit reports.

The phone works fully offline. When the shop is put online, staff can also use the web app with their own Google account, and every phone and browser sees the same records within seconds.

**[Download the latest APK](https://github.com/ocpabdurrahim/electropos/releases/latest)** (Android 7.0 or newer) · **[Open the web app](https://inventory-management-pos.web.app)**

## Features

- **Point of sale:** search or scan a barcode, SKU or IMEI; variants (8/128 Black…); pick the exact IMEI or serial unit; edit the price down to the minimum price; line and invoice discounts; cash, bKash, Nagad, Rocket, card or bank; part payments put the rest on the customer's account. Invoices as A4, A5 or 80 mm receipts, to print, save as PDF or share.
- **Stock:** products with categories, subcategories, brands and units; quantity, IMEI, serial or serial + IMEI tracking; low-stock alerts; stock adjustments (damaged, lost, internal use) with a full movement history; weighted average cost.
- **Purchases:** buy from suppliers with IMEI/serial entry, pay in full or in part, purchase returns and cancellations.
- **Returns:** sales returns by item or by IMEI unit, with cash refund or account credit; damaged returns stay out of stock.
- **Customers and suppliers:** codes, ledgers, dues, payments that settle the oldest bills, opening balances.
- **Money:** expenses by category, cash book with cash in/out and transfers between cash, bKash, bank and the rest.
- **Reports:** sales, profit after returns and expenses, purchases, stock value, dues, top products; by day, month or any period; CSV export.
- **Warranty:** every sold unit's warranty with its end date, and claims from received to returned.
- **Staff:**

  | Role | Can do |
  |---|---|
  | Owner | Everything; puts the shop online and adds staff |
  | Admin | Everything, including members and PIN users |
  | Manager | Everything except members and PIN users |
  | Salesperson | Sell, returns, customers and warranty; sees no costs, profit, purchases or settings |

  On the phone, staff log in at the counter with a PIN; online, each person signs in with their Google account. The server enforces the roles, not only the apps.
- **Backup & Restore** on the phone (a single file you can keep anywhere), plus Excel-ready CSV export.
- Android extras: PIN lock with lockout, light and dark themes, and automatic updates from this repository's releases.

## How it is built

| Part | What |
|---|---|
| Android | Kotlin, Jetpack Compose, Material 3, Room (the shop lives on the phone); sync through Cloud Firestore; Google sign-in through Credential Manager |
| Web | React, TypeScript, Vite; the whole shop in memory with Firestore's browser cache; Firebase Hosting |
| Server | Cloud Firestore and Firebase Auth. [`firebase/firestore.rules`](firebase/firestore.rules) gives each shop's staff access by role |

Both apps follow one written contract, [docs/data-model.md](docs/data-model.md): where every record lives, how ids stay unique across devices (each device takes its own block of ids), how invoice numbers carry a till code (`INV-2026-A00042` from the first phone, `W` from the web), and the rules both apps compute the same way (stock, average cost, dues, invoice settlement).

## Folders

```
electropos/
├── app/                       the Android app
│   └── src/main/java/com/example/
│       ├── data/              Room entities, DAOs and the repositories with the shop's rules
│       ├── sync/              two-way cloud sync: id blocks, change triggers, push/pull, caches
│       ├── core/              invoices (PDF/print), security (PINs, roles), helpers
│       ├── update/            self-update from GitHub releases
│       └── ui/                Compose screens
├── web/                       the web app (React + Vite)
│   ├── src/                   pages, the Firestore store, the same rules as the phone
│   └── rules/                 tests for the security rules, run against the Firestore emulator
├── firebase/                  Cloud Firestore security rules and indexes
├── docs/                      the data model both apps follow, release notes
├── firebase.json              hosting (web/dist), rules, local emulators
└── .github/                   CI: build.yml (phone app and releases), web.yml (web app and rules)
```

## Building

GitHub Actions builds every push:

- [`build.yml`](.github/workflows/build.yml), the Android app:
  1. unit tests (including two-phone sync tests), plus the debug and release APKs;
  2. a run on an Android 14 emulator against local Firebase emulators: instrumented tests, the self-update test and a smoke test of the release APK;
  3. on `main`, when `appVersionName` is newer than the latest release and everything above passed: the release publishes itself (tag `vX.Y.Z` plus the signed APK).
- [`web.yml`](.github/workflows/web.yml), the web app: unit tests, security-rules tests against the Firestore emulator, and a production build.

Installed phones update themselves from the latest release. They check `releases/latest`, verify the APK's SHA-256, package name, version and signing certificate, and install it once the app is in the background.

**To ship a new version:** raise `appVersionName` in [`app/build.gradle.kts`](app/build.gradle.kts) (for example `1.0.0` → `1.0.1`), put what changed in `docs/release-notes.md`, and push to `main`. The version code follows from the name (1.2.3 → 10203); CI tests the build and publishes the release.

Locally, the phone app (needs the Android SDK and JDK 17+):

```bash
./gradlew assembleDebug
```

The web app:

```bash
npm --prefix web install
```

```bash
npm --prefix web run build
```

```bash
npx firebase-tools deploy --only hosting,firestore
```

The Android build needs `app/google-services.json`, which is not in the repository. CI writes it from the `FIREBASE_GOOGLE_SERVICES` secret; without it, it uses `app/google-services.demo.json`, which works only with the Firebase emulators. To try everything against the emulators:

```bash
npx firebase-tools emulators:start --only auth,firestore --project demo-electropos
```

```bash
npm --prefix web run seed:emulator
```

```bash
npm --prefix web run dev:emulator
```

A debug build of the phone app talks to the same emulators when built with `-PfirebaseEmulatorHost=10.0.2.2` (from an Android emulator) and the demo config.

Release builds are signed with the key from the `EPOS_KEYSTORE_*` repository secrets.

## Data and privacy

Until you put the shop online, everything stays on the phone. Online, a shop's records are stored in this project's Cloud Firestore database; only that shop's active staff can read them, and the security rules decide what each role may change. Nothing is hard-deleted: a deleted record is kept as a marker, so every device learns about the deletion. Theme, PIN login and update settings stay on each phone.
