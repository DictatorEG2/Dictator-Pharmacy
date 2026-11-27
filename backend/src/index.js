const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...corsHeaders() }
});
function corsHeaders() {
  return { "access-control-allow-origin": "*", "access-control-allow-methods": "GET,POST,OPTIONS", "access-control-allow-headers": "authorization,content-type" };
}
const now = () => Math.floor(Date.now() / 1000);
const randomToken = (bytes = 32) => {
  const data = new Uint8Array(bytes); crypto.getRandomValues(data);
  return Array.from(data, b => b.toString(16).padStart(2, "0")).join("");
};
async function hash(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
}
async function body(request) {
  const raw = await request.text();
  if (raw.length > 8192) throw new Error("body_too_large");
  return raw ? JSON.parse(raw) : {};
}
async function auth(request, env) {
  const token = request.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
  if (!token || token.length < 32) return null;
  const tokenHash = await hash(token);
  const device = await env.DB.prepare("SELECT id,name,role,status,pharmacy_id FROM devices WHERE token_hash=? AND status='active' AND pharmacy_id IS NOT NULL").bind(tokenHash).first();
  if (device) await env.DB.prepare("UPDATE devices SET last_seen_at=? WHERE id=? AND pharmacy_id=?").bind(now(), device.id, device.pharmacy_id).run();
  return device || null;
}
async function manager(request, env) {
  const d = await auth(request, env);
  return d?.role === "manager" ? d : null;
}
async function audit(env, pharmacyId, actor, action, target, details = null) {
  await env.DB.prepare("INSERT INTO audit_log(pharmacy_id,actor_device_id,action,target_id,created_at,details_json) VALUES(?,?,?,?,?,?)")
    .bind(pharmacyId, actor?.id || null, action, target || null, now(), details ? JSON.stringify(details) : null).run();
}
async function createPharmacy(request, env) {
  const input = await body(request);
  const pharmacyName = String(input.pharmacyName || "").trim().slice(0, 100);
  const deviceName = String(input.deviceName || "").trim().slice(0, 80);
  if (!pharmacyName || !deviceName) return json({ error: "pharmacy_name_and_device_name_required" }, 400);

  // Hash the Cloudflare-provided client IP; never store the raw address.
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const ipHash = await hash(ip + (env.RATE_LIMIT_SALT || "dictator-pharmacy-registration"));
  const timestamp = now();
  await env.DB.prepare(`INSERT INTO registration_limits(ip_hash,window_started_at,attempts) VALUES(?,?,1)
    ON CONFLICT(ip_hash) DO UPDATE SET
      attempts=CASE WHEN registration_limits.window_started_at<=? THEN 1 ELSE registration_limits.attempts+1 END,
      window_started_at=CASE WHEN registration_limits.window_started_at<=? THEN excluded.window_started_at ELSE registration_limits.window_started_at END`)
    .bind(ipHash, timestamp, timestamp - 3600, timestamp - 3600).run();
  const limit = await env.DB.prepare("SELECT attempts FROM registration_limits WHERE ip_hash=?").bind(ipHash).first();
  if ((limit?.attempts || 0) > 5) return json({ error: "registration_rate_limited", retryAfterSeconds: 3600 }, 429);

  const pharmacyId = crypto.randomUUID();
  const deviceId = crypto.randomUUID();
  const token = randomToken();
  const tokenHash = await hash(token);
  const results = await env.DB.batch([
    env.DB.prepare("INSERT INTO pharmacies(id,name,created_at,status) VALUES(?,?,?,'active')").bind(pharmacyId, pharmacyName, timestamp),
    env.DB.prepare("INSERT INTO devices(id,pharmacy_id,name,role,status,token_hash,created_at,approved_at,last_seen_at) SELECT ?,?,?,'manager','active',?,?,?,? WHERE EXISTS(SELECT 1 FROM pharmacies WHERE id=?)")
      .bind(deviceId, pharmacyId, deviceName, tokenHash, timestamp, timestamp, timestamp, pharmacyId),
    env.DB.prepare("INSERT INTO audit_log(pharmacy_id,actor_device_id,action,target_id,created_at) VALUES(?,?,'pharmacy_created',?,?)")
      .bind(pharmacyId, deviceId, pharmacyId, timestamp)
  ]);
  if (!results?.[1]?.meta?.changes) return json({ error: "pharmacy_creation_failed" }, 500);
  return json({ pharmacyId, pharmacyName, deviceId, token, role: "manager" }, 201);
}
async function handle(request, env) {
  if (request.method === "OPTIONS") return new Response(null, { headers: corsHeaders() });
  const url = new URL(request.url), path = url.pathname;
  if (path === "/health" && request.method === "GET") return json({ ok: true, service: "dictator-pharmacy-sync", version: 2, multiTenant: true });
  if (path === "/v1/pharmacies" && request.method === "POST") return createPharmacy(request, env);
  // The old global bootstrap model is intentionally disabled.
  if (path === "/v1/setup/manager" && request.method === "POST")
    return json({ error: "legacy_global_setup_disabled", use: "POST /v1/pharmacies" }, 410);

  if (path === "/v1/enroll" && request.method === "POST") {
    const input = await body(request), code = String(input.code || "").trim().toUpperCase();
    const name = String(input.deviceName || "").trim().slice(0, 80);
    if (!/^[A-F0-9]{12}$/.test(code) || !name) return json({ error: "invalid_code_or_device_name" }, 400);
    const codeHash = await hash(code), timestamp = now();
    const invite = await env.DB.prepare("SELECT code_hash,pharmacy_id,expires_at,used_at FROM invitations WHERE code_hash=?").bind(codeHash).first();
    if (!invite || invite.used_at || invite.expires_at < timestamp || !invite.pharmacy_id) return json({ error: "invite_invalid_or_expired" }, 410);
    const id = crypto.randomUUID();
    const results = await env.DB.batch([
      env.DB.prepare("UPDATE invitations SET used_at=? WHERE code_hash=? AND used_at IS NULL AND expires_at>=? AND pharmacy_id=?")
        .bind(timestamp, codeHash, timestamp, invite.pharmacy_id),
      env.DB.prepare("INSERT INTO devices(id,pharmacy_id,name,role,status,created_at) SELECT ?,?,?,'staff','pending',? WHERE changes()=1")
        .bind(id, invite.pharmacy_id, name, timestamp)
    ]);
    if (!results?.[1]?.meta?.changes) return json({ error: "invite_invalid_or_expired" }, 410);
    await audit(env, invite.pharmacy_id, null, "device_enrollment_requested", id);
    return json({ deviceId: id, pharmacyId: invite.pharmacy_id, status: "pending", message: "Awaiting manager approval" }, 202);
  }

  if (path === "/v1/invites" && request.method === "POST") {
    const actor = await manager(request, env);
    if (!actor) return json({ error: "manager_auth_required" }, 403);
    const code = randomToken(6).toUpperCase(), timestamp = now(), expiry = timestamp + 600;
    await env.DB.prepare("INSERT INTO invitations(code_hash,pharmacy_id,created_by,expires_at,created_at) VALUES(?,?,?,?,?)")
      .bind(await hash(code), actor.pharmacy_id, actor.id, expiry, timestamp).run();
    await audit(env, actor.pharmacy_id, actor, "invite_created", null, { expiresAt: expiry });
    return json({ code, expiresAt: expiry, expiresInSeconds: 600 }, 201);
  }

  if (path === "/v1/manager/devices" && request.method === "GET") {
    const actor = await manager(request, env);
    if (!actor) return json({ error: "manager_auth_required" }, 403);
    const { results } = await env.DB.prepare("SELECT id,name,role,status,created_at,approved_at,last_seen_at FROM devices WHERE pharmacy_id=? ORDER BY created_at DESC LIMIT 100")
      .bind(actor.pharmacy_id).all();
    return json({ devices: results || [] });
  }

  const deviceAction = path.match(/^\/v1\/manager\/devices\/([a-f0-9-]+)\/(approve|revoke)$/i);
  if (deviceAction && request.method === "POST") {
    const actor = await manager(request, env);
    if (!actor) return json({ error: "manager_auth_required" }, 403);
    const [, targetId, action] = deviceAction;
    if (targetId === actor.id) return json({ error: "cannot_change_manager_device" }, 400);
    if (action === "approve") {
      const token = randomToken(), tokenHash = await hash(token), timestamp = now();
      const result = await env.DB.prepare("UPDATE devices SET status='active',token_hash=?,approved_at=? WHERE id=? AND pharmacy_id=? AND role='staff' AND status='pending'")
        .bind(tokenHash, timestamp, targetId, actor.pharmacy_id).run();
      if (!result.meta?.changes) return json({ error: "pending_device_not_found" }, 404);
      await audit(env, actor.pharmacy_id, actor, "device_approved", targetId);
      return json({ deviceId: targetId, status: "active", token }, 200);
    }
    const result = await env.DB.prepare("UPDATE devices SET status='revoked',token_hash=NULL WHERE id=? AND pharmacy_id=? AND role='staff' AND status!='revoked'")
      .bind(targetId, actor.pharmacy_id).run();
    if (!result.meta?.changes) return json({ error: "device_not_found" }, 404);
    await audit(env, actor.pharmacy_id, actor, "device_revoked", targetId);
    return json({ deviceId: targetId, status: "revoked" });
  }

  const device = await auth(request, env);
  if (!device) return json({ error: "authentication_required" }, 401);
  if (path === "/v1/me" && request.method === "GET")
    return json({ deviceId: device.id, deviceName: device.name, role: device.role, status: device.status, pharmacyId: device.pharmacy_id });
  return json({ error: "not_found" }, 404);
}
export default {
  async fetch(request, env) {
    try { return await handle(request, env); }
    catch (error) {
      const message = error instanceof SyntaxError ? "invalid_json" : (error?.message || "internal_error");
      const status = message === "body_too_large" ? 413 : message === "invalid_json" ? 400 : 500;
      return json({ error: status === 500 ? "internal_error" : message }, status);
    }
  }
};
