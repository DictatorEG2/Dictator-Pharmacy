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

## Delivery status
- Android: pharmacy creation, device-token sign-in, staff invite enrollment, manager device approval/revocation, and pharmacy-specific local Room database.
- Cloudflare API: tenant-scoped medicine catalog, stock batches, FEFO sale creation, customer records, sale history/details, partial payments, and date-range sales summaries.
- CI: Android debug APK build plus Worker JavaScript syntax validation.
- Still in progress: wiring medicine, inventory, sales and customer screens to the API; reliable offline outbox/retry synchronization; returns; backup/restore; signed GitHub Releases updates; and full tenant-isolation/transaction tests.

## Operational note
Do not use the app for live pharmacy operations until sales, synchronization, backups, permissions and restore have been implemented and tested. Medicine categories are organizational aids, not clinical advice. Verify prices, product identity and expiry information against the physical package and supplier records.

## Build
Open the project in Android Studio with JDK 17 and Android SDK 35, sync Gradle, then build the app module.

## Cloudflare backend
The Cloudflare Worker and D1 database are deployed. Migrations 0001–0004 have already been applied to the production D1 database; do not rerun them. The Worker now includes tenant-scoped pharmacy/device management, medicine catalog, inventory batches, sale creation/history/details, customer records, partial payments, and reports. The Android app does not yet synchronize its existing local medicine, inventory, sales or debt screens with these endpoints. Configure `RATE_LIMIT_SALT` only as a Worker secret; never put it in the APK. See `backend/README.md` for API details.
