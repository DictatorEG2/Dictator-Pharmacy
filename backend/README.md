# Dictator Pharmacy Sync API

Cloudflare Worker + D1 API for independent pharmacies (multi-tenant).

## Tenant and security model
- Every pharmacy registers itself with `POST /v1/pharmacies`; the first device is automatically its manager.
- Each pharmacy receives a unique `pharmacyId`. Every manager/staff device and invitation is scoped to that pharmacy.
- Device tokens are returned only once at issuance; only SHA-256 hashes are stored in D1.
- Registration is rate-limited to five attempts per client IP per hour; only a hash of the IP is stored.
- Staff join using a one-time invitation valid for 10 minutes and require manager approval.
- Manager approval/revocation and device listings are constrained by the authenticated manager's pharmacy ID.
- The old global `PHARMACY_BOOTSTRAP_SECRET` setup route is disabled. Never embed service secrets in the APK.
- The Android app must store device tokens in Android Keystore-backed encrypted storage.

## Required database migration
Apply `migrations/0002_multi_pharmacy.sql` to the existing D1 database before deploying this Worker version. In Cloudflare D1 Console, run the migration SQL once. Do not rerun it: SQLite `ALTER TABLE ADD COLUMN` cannot be repeated.

## Deploy
1. Confirm the D1 binding is named `DB` and points to `dictator-pharmacy`.
2. Apply migration `0002_multi_pharmacy.sql` to the remote D1 database.
3. Deploy this Worker from the `backend` directory with `npx wrangler deploy`.

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
- This API currently implements pharmacy registration and device enrollment only.
- Inventory, sales, debts, backup/restore, synchronization, conflict resolution, and Android registration UI are not implemented yet.
- Do not use real pharmacy data until those features and tenant-isolation tests are complete.
- Before production launch, add stronger abuse protection (for example Turnstile), monitoring, retention policies, and automated tests for cross-tenant access.
