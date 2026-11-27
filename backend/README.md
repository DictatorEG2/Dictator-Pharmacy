# Dictator Pharmacy Sync API

Cloudflare Worker + D1 foundation for manager-controlled device enrollment.

## Security model
- The first manager is initialized once using the Worker secret `PHARMACY_BOOTSTRAP_SECRET`.
- The manager issues one-time invitation codes valid for 10 minutes.
- A staff device submits the code and enters a pending state.
- The manager explicitly approves or revokes staff devices.
- Raw device tokens are returned only at issuance; only SHA-256 token hashes are stored in D1.
- The app must store issued tokens in Android Keystore-backed encrypted storage. Never embed the bootstrap secret in the APK.
- This API currently handles enrollment and device access only. Inventory, sales, debts, conflict resolution, and sync endpoints are not implemented yet.

## Deploy
1. Create a Cloudflare D1 database named `dictator-pharmacy`.
2. Replace the placeholder `database_id` in `wrangler.toml`.
3. Run `npx wrangler d1 migrations apply dictator-pharmacy --remote`.
4. Set a long random Worker secret: `npx wrangler secret put PHARMACY_BOOTSTRAP_SECRET`.
5. Deploy from this directory with `npx wrangler deploy`.
6. Call `POST /v1/setup/manager` once with JSON `{"deviceName":"Manager"}` and header `x-bootstrap-secret`. Keep the returned token private; it is the manager credential.

## API
- `GET /health`
- `POST /v1/setup/manager` (one-time bootstrap secret)
- `POST /v1/invites` (manager bearer token; returns a 10-minute one-use code)
- `POST /v1/enroll` (JSON: `{"code":"...","deviceName":"..." }`; returns pending device ID)
- `GET /v1/manager/devices` (manager bearer token)
- `POST /v1/manager/devices/{id}/approve` (manager bearer token; returns staff token once)
- `POST /v1/manager/devices/{id}/revoke` (manager bearer token)
- `GET /v1/me` (device bearer token)

## Important
The Worker is not deployed by committing these files. Configure the D1 ID and secret in Cloudflare and deploy it before connecting the Android app. Use HTTPS only. Add rate limiting/WAF rules and operational monitoring before production use. Do not use real pharmacy data until the full sync protocol is implemented and tested.
