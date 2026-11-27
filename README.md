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
The Cloudflare Worker and D1 database are deployed. The Worker currently provides independent pharmacy registration, one-time staff invitations, pending enrollment, manager approval/revocation, and hashed device-token authentication. The `backend/migrations/0003_pharmacy_business_data.sql` migration adds tenant-scoped medicines, stock batches, sales, customers, payments, stock movements, and sync-idempotency tables; apply it once to the existing D1 database. Data synchronization endpoints and Android integration are still under development. Configure `RATE_LIMIT_SALT` as a Worker secret; never put it in the APK. See `backend/README.md` for current setup and migration instructions.
