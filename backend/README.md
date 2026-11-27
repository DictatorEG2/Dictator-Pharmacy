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

## Current limitations
- The current Worker implements pharmacy registration and device enrollment only. Migrations `0003` and `0004` provide tenant-scoped business tables; medicine, inventory, sales, customer-ledger, and synchronization endpoints and conflict-resolution logic are still pending.
- Backup/restore and Android registration/synchronization UI are not implemented yet.
- Do not use real pharmacy data until those features and tenant-isolation tests are complete.
- Before production launch, add stronger abuse protection (for example Turnstile), monitoring, retention policies, and automated tests for cross-tenant access.
