# Dictator Pharmacy Sync API

Cloudflare Worker + D1 API for independent pharmacies (multi-tenant).

## Tenant and security model
- Every pharmacy registers itself with `POST /v1/pharmacies`; the first device is automatically its manager.
- Each pharmacy receives a unique `pharmacyId`. Every manager/staff device and invitation is scoped to that pharmacy.
- Device tokens are returned only once at issuance; only SHA-256 hashes are stored in D1.
- Pharmacy creation is limited to five attempts per client IP per hour, and staff enrollment to 20 attempts per client IP per hour; only salted IP hashes are stored. Configure the Worker secret `RATE_LIMIT_SALT` (at least 32 random characters).
- Staff join using a one-time invitation valid for 10 minutes and require manager approval.
- Manager approval/revocation and device listings are constrained by the authenticated manager's pharmacy ID.
- The old global `PHARMACY_BOOTSTRAP_SECRET` setup route is disabled. Never embed service secrets in the APK.
- The Android app must store device tokens in Android Keystore-backed encrypted storage.

## Required database migrations
The existing database has migrations `0001_initial.sql`, `0002_multi_pharmacy.sql`, and `0003_pharmacy_business_data.sql` applied. Apply the new `migrations/0004_walk_in_sale_payments.sql` exactly once to the same D1 database. It rebuilds `sale_payments` to allow a nullable customer for walk-in sales while preserving existing payment rows. Run the SQL in Cloudflare D1 Console. Never rerun an applied migration.

## Deploy
1. Confirm the D1 binding is named `DB` and points to `dictator-pharmacy`.
2. Apply any pending migration exactly once (currently `0004_walk_in_sale_payments.sql` for databases that already applied `0003`).
3. Configure the `RATE_LIMIT_SALT` Worker secret.
4. Deploy this Worker from the `backend` directory with `npx wrangler deploy`.

## API
- `GET /health`
- `POST /v1/pharmacies` JSON: `{"pharmacyName":"My Pharmacy","deviceName":"Manager phone"}`; creates an independent pharmacy and returns its manager token once.
- `POST /v1/invites` (manager bearer token; returns a 10-minute one-use code)
- `POST /v1/enroll` JSON: `{"code":"...","deviceName":"Staff phone"}`; returns a pending device scoped to the invitation's pharmacy.
- `GET /v1/manager/devices` (manager bearer token; current pharmacy only)
- `POST /v1/manager/devices/{id}/approve` (manager bearer token; same pharmacy only; returns staff token once)
- `POST /v1/manager/devices/{id}/revoke` (manager bearer token; same pharmacy only)
- `GET /v1/me` (device bearer token)
- `GET /v1/medicines?q=part&category=...` (device bearer token; tenant-scoped search, 20 rows per page; optional `page` parameter)
- `POST /v1/medicines` (device bearer token; create a medicine using `name`, optional `category`, `barcode`, `imageUrl`, `unit`, `salePriceMinor`, and `lowStockThreshold`)
- `PUT /v1/medicines/{id}` (device bearer token; update an active medicine; send `name`, `salePriceMinor`, and `lowStockThreshold`, with optional metadata)
- `DELETE /v1/medicines/{id}` (device bearer token; soft-deactivate the medicine without deleting its history)
- `GET /v1/inventory/batches?medicineId=...&page=1` (device bearer token; pharmacy-scoped batch listing ordered by nearest expiry, 20 rows per page)
- `POST /v1/inventory/batches` (device bearer token; receive stock for an active medicine; JSON fields: `medicineId`, positive integer `quantity`, optional `batchNumber`, optional ISO `expiryDate`, optional integer `purchasePriceMinor`; batch and purchase movement are written together)
- `POST /v1/sales` (device bearer token; creates a sale and deducts stock FEFO in one D1 batch). JSON: `{"operationId":"client-generated-stable-id","items":[{"medicineId":"uuid","quantity":2}],"customerId":null,"paymentAmountMinor":5000,"paymentMethod":"cash"}`. `operationId` is required (16–80 URL-safe characters) and makes retries idempotent; reusing it with a different payload returns 409. Item quantities are positive integers; the server reads active medicine prices and calculates totals itself. Optional `customerId` supports walk-in sales; `paymentAmountMinor` defaults to zero and may be partial, while payment methods are `cash`, `card`, `transfer`, or `other`.

## Current limitations
- The Worker implements pharmacy registration, device enrollment, a tenant-scoped medicine catalog, inventory batch receiving/listing, and sale creation. Sale creation allocates unexpired stock by FEFO, conditionally decrements every selected batch, and writes the sale, item snapshots, stock movements, optional payment, idempotency record, and audit entry in one D1 batch. A stale stock allocation fails closed; insufficient stock returns HTTP 409. Repeated operation IDs with the same payload return the original result.
- Backup/restore and Android registration/synchronization UI are not implemented yet.
- Do not use real pharmacy data until those features and tenant-isolation tests are complete.
- Before production launch, add stronger abuse protection (for example Turnstile), monitoring, retention policies, and automated tests for cross-tenant access.
