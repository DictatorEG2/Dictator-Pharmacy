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
The existing database already has migrations `0001_initial.sql` and `0002_multi_pharmacy.sql` applied. Apply `migrations/0003_pharmacy_business_data.sql` once to the same D1 database before deploying business-data API endpoints. Run the SQL in Cloudflare D1 Console. Do not rerun migrations containing schema changes.

## Deploy
1. Confirm the D1 binding is named `DB` and points to `dictator-pharmacy`.
2. Apply any pending migration exactly once (currently `0003_pharmacy_business_data.sql`).
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
- The current Worker implements pharmacy registration and device enrollment. The `0003` migration adds tenant-scoped tables for medicines, expiring stock batches, sales, sale items, stock movements, customers, payments, and sync idempotency; corresponding data endpoints and conflict-resolution logic are still pending.
- Backup/restore and Android registration/synchronization UI are not implemented yet.
- Do not use real pharmacy data until those features and tenant-isolation tests are complete.
- Before production launch, add stronger abuse protection (for example Turnstile), monitoring, retention policies, and automated tests for cross-tenant access.
