# DICTATOR Pharmacy Management System

Arabic-first, offline-first Android pharmacy management app.

**Developed by Dictator_EG@ & @afouly99**

## First-release scope
- Medicine catalog: partial-name search, categories, price, barcode and image.
- Stock: arbitrary add/subtract quantities, low-stock and out-of-stock lists, movement history.
- Sales: atomic stock deduction and daily/weekly/monthly best-seller reports.
- Debtors: customer balances, itemized purchases, partial payments and settlement. PDF/Excel/Word exports remain outstanding.
- Manager/staff roles with pharmacy join code or QR and multi-device synchronization.
- Planned: encrypted manual/automatic backups with restore (not implemented yet).
- GitHub Releases update checks and APK installation flow.
- Arabic RTL and Dictator visual identity: black, dark crimson and dark electric blue.

## Engineering rules
- The medicine reference catalogue is local/offline. Sales, stock changes and debt operations currently require internet; an offline outbox/retry system is a release blocker.
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


## Sale discounts

Percentage discounts are applied to the gross sale amount and persisted as a separate `discount_minor` value for auditability. **Apply backend migration `0005_sale_discounts.sql` before deploying the updated Worker**; do not deploy the updated Worker before applying the migration.

## Egyptian medicine reference catalogue

The Android app bundles and imports the public `eg-drugs` Egyptian medicine dataset, containing more than 27,000 records with Arabic and English trade names, active ingredients, manufacturers, reference prices, barcodes, categories, and usage text. The catalogue is browsable without typing a search and is paginated locally in groups of 20. It is separate from each pharmacy's actual stock and does not automatically create inventory items.

Data source: [Egyptian Drug Database (eg-drugs)](https://github.com/mahmoudfalous/eg-drugs) by Mahmoud Mohamed. Dataset licence: CC BY 4.0. Attribution is retained here. Reference prices may be outdated or differ from a pharmacy's actual selling price; managers set their own selling price when adding stock.

Package photographs are not included in this dataset. The app displays a saved pharmacy product photo when one exists and otherwise clearly uses a neutral medicine placeholder. Do not mistake that placeholder for the real package.
