# DICTATOR Pharmacy Management System

Arabic-first, offline-first Android pharmacy management app.

**Developed by Dictator_EG@ & @afouly99**

## First-release scope
- Medicine catalog: partial-name search, categories, price, barcode and image.
- Stock: arbitrary add/subtract quantities, low-stock and out-of-stock lists, movement history.
- Sales: atomic stock deduction and daily/weekly/monthly best-seller reports.
- Debtors: customer balances, itemized purchases, partial payments, full settlement, sorting and Excel/PDF export.
- Manager/staff roles with pharmacy join code or QR and multi-device synchronization.
- Automatic and manual encrypted backups with restore.
- GitHub Releases update checks and APK installation flow.
- Arabic RTL and Dictator visual identity: black, dark crimson and dark electric blue.

## Engineering rules
- Local-first storage; sales must remain possible during internet outages.
- SQLite indexes for frequent lookups.
- No N+1 queries and no SELECT * in server-side queries.
- Paginate large lists in pages of 20.
- Cache static category/reference data.
- Stock and payment changes must be transactional and auditable.
- Never allow negative stock or negative customer balances.
- Keep secrets out of the APK. Use a stable signing key for every release.
- Database migrations must preserve existing pharmacy data.

## Planned phases
1. Android foundation, inventory and stock adjustment.
2. Sales, returns and best-seller reporting.
3. Debtors, partial payments and exports.
4. Manager/staff authentication and Cloudflare-backed synchronization.
5. Encrypted backup/restore and recovery validation.
6. GitHub Releases update checker, signing and release workflow.

## Operational note
Do not use the app for live pharmacy operations until sales, synchronization, backups, permissions and restore have been implemented and tested. Medicine categories are organizational aids, not clinical advice. Verify prices, product identity and expiry information against the physical package and supplier records.

## Build
Open the project in Android Studio with JDK 17 and Android SDK 35, sync Gradle, then build the app module.

## Cloudflare backend foundation
The initial Worker and D1 schema are in `backend/`. They implement one-time manager bootstrap, expiring single-use invitations, pending staff enrollment, manager approval/revocation, and hashed device-token storage. This is an enrollment foundation only; the Worker is not deployed and inventory/sales/debt synchronization is not implemented yet. Follow `backend/README.md` to create the D1 database, configure the database ID and bootstrap secret, apply migrations, and deploy. Do not put the bootstrap secret in the Android app.
