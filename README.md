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


## Egyptian medicine reference catalog

The Android build downloads the CC0 Egyptian medicine reference dataset from `karem505/egyptian-drug-database` and bundles it in the APK. It contains approximately 25,070 Arabic/English medicine trade-name records with active ingredients, manufacturers, routes, classifications and reference prices. The app imports the list into a local indexed Room table for offline search and refreshes it from the source about once per day when internet is available.

This is a community-maintained dataset, **not an official EDA feed**. The EDA registry remains the authority for current registration status and official product details. The reference price is not the pharmacy's selling price: the manager must confirm and set the actual sale price and stock quantity before adding a medicine to the pharmacy catalog. The source dataset does not provide product photographs; the app supports attaching a local medicine photo, which is currently stored on that device only and is not synced to other devices.

Source and license: [Egyptian Drug Database (CC0-1.0)](https://github.com/karem505/egyptian-drug-database). Verify medicine registration and details through the [Egyptian Drug Authority registry](https://eservices.edaegypt.gov.eg/EDASearch/SearchRegDrugs.aspx).

## Sale discounts

Percentage discounts are applied to the gross sale amount and persisted as a separate `discount_minor` value for auditability. **Apply backend migration `0005_sale_discounts.sql` before deploying the updated Worker**; do not deploy the updated Worker before applying the migration.
