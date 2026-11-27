const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...corsHeaders() }
});
function corsHeaders() {
  return { "access-control-allow-origin": "*", "access-control-allow-methods": "GET,POST,OPTIONS", "access-control-allow-headers": "authorization,content-type,x-bootstrap-secret" };
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
  const device = await env.DB.prepare("SELECT id,name,role,status FROM devices WHERE token_hash=? AND status='active'").bind(tokenHash).first();
  if (device) await env.DB.prepare("UPDATE devices SET last_seen_at=? WHERE id=?").bind(now(), device.id).run();
  return device || null;
}
async function manager(request, env) {
  const d = await auth(request, env);
  return d?.role === "manager" ? d : null;
}
async function audit(env, actor, action, target, details = null) {
  await env.DB.prepare("INSERT INTO audit_log(actor_device_id,action,target_id,created_at,details_json) VALUES(?,?,?,?,?)")
    .bind(actor?.id || null, action, target || null, now(), details ? JSON.stringify(details) : null).run();
}
async function handle(request, env) {
  if (request.method === "OPTIONS") return new Response(null, { headers: corsHeaders() });
  const url = new URL(request.url), path = url.pathname;
  if (path === "/health" && request.method === "GET") return json({ ok: true, service: "dictator-pharmacy-sync", version: 1 });

  if (path === "/v1/setup/manager" && request.method === "POST") {
    const input = await body(request);
    if (!env.PHARMACY_BOOTSTRAP_SECRET || request.headers.get("x-bootstrap-secret") !== env.PHARMACY_BOOTSTRAP_SECRET)
      return json({ error: "invalid_bootstrap_secret" }, 401);
    const state = await env.DB.prepare("SELECT manager_device_id FROM pharmacy_state WHERE id=1").first();
    if (state?.manager_device_id) return json({ error: "manager_already_configured" }, 409);
    const name = String(input.deviceName || "").trim().slice(0, 80);
    if (!name) return json({ error: "device_name_required" }, 400);
    const id = crypto.randomUUID(), token = randomToken();
    const tokenHash = await hash(token), timestamp = now();
    const result = await env.DB.batch([
      env.DB.prepare("UPDATE pharmacy_state SET manager_device_id=? WHERE id=1 AND manager_device_id IS NULL").bind(id),
      env.DB.prepare("INSERT INTO devices(id,name,role,status,token_hash,created_at,approved_at,last_seen_at) SELECT ?,?,'manager','active',?,?,?,? WHERE EXISTS (SELECT 1 FROM pharmacy_state WHERE id=1 AND manager_device_id=?)")
        .bind(id, name, tokenHash, timestamp, timestamp, timestamp, id)
    ]);
    if (!result?.[1]?.meta?.changes) return json({ error: "manager_already_configured" }, 409);
    await audit(env, { id }, "manager_initialized", id);
    return json({ deviceId: id, token, role: "manager" }, 201);
  }

  if (path === "/v1/enroll" && request.method === "POST") {
    const input = await body(request), code = String(input.code || "").trim().toUpperCase();
    const name = String(input.deviceName || "").trim().slice(0, 80);
    if (!/^[A-F0-9]{12}$/.test(code) || !name) return json({ error: "invalid_code_or_device_name" }, 400);
    const codeHash = await hash(code), timestamp = now();
    const invite = await env.DB.prepare("SELECT code_hash,created_by,expires_at,used_at FROM invitations WHERE code_hash=?").bind(codeHash).first();
    if (!invite || invite.used_at || invite.expires_at < timestamp) return json({ error: "invite_invalid_or_expired" }, 410);
    const id = crypto.randomUUID();
    const claimed = await env.DB.prepare("UPDATE invitations SET used_at=? WHERE code_hash=? AND used_at IS NULL AND expires_at>=?")
      .bind(timestamp, codeHash, timestamp).run();
    if (!claimed.meta?.changes) return json({ error: "invite_invalid_or_expired" }, 410);
    await env.DB.prepare("INSERT INTO devices(id,name,role,status,created_at) VALUES(?,?,'staff','pending',?)")
      .bind(id, name, timestamp).run();
    await audit(env, null, "device_enrollment_requested", id);
    return json({ deviceId: id, status: "pending", message: "Awaiting manager approval" }, 202);
  }

  if (path === "/v1/invites" && request.method === "POST") {
    const actor = await manager(request, env);
    if (!actor) return json({ error: "manager_auth_required" }, 403);
    const code = randomToken(6).toUpperCase(), timestamp = now(), expiry = timestamp + 600;
    await env.DB.prepare("INSERT INTO invitations(code_hash,created_by,expires_at,created_at) VALUES(?,?,?,?)")
      .bind(await hash(code), actor.id, expiry, timestamp).run();
    await audit(env, actor, "invite_created", null, { expiresAt: expiry });
    return json({ code, expiresAt: expiry, expiresInSeconds: 600 }, 201);
  }

  if (path === "/v1/manager/devices" && request.method === "GET") {
    const actor = await manager(request, env);
    if (!actor) return json({ error: "manager_auth_required" }, 403);
    const { results } = await env.DB.prepare("SELECT id,name,role,status,created_at,approved_at,last_seen_at FROM devices ORDER BY created_at DESC LIMIT 100").all();
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
      const result = await env.DB.prepare("UPDATE devices SET status='active',token_hash=?,approved_at=? WHERE id=? AND role='staff' AND status='pending'")
        .bind(tokenHash, timestamp, targetId).run();
      if (!result.meta?.changes) return json({ error: "pending_device_not_found" }, 404);
      await audit(env, actor, "device_approved", targetId);
      return json({ deviceId: targetId, status: "active", token }, 200);
    }
    const result = await env.DB.prepare("UPDATE devices SET status='revoked',token_hash=NULL WHERE id=? AND role='staff' AND status!='revoked'").bind(targetId).run();
    if (!result.meta?.changes) return json({ error: "device_not_found" }, 404);
    await audit(env, actor, "device_revoked", targetId);
    return json({ deviceId: targetId, status: "revoked" });
  }

  const device = await auth(request, env);
  if (!device) return json({ error: "authentication_required" }, 401);
  if (path === "/v1/me" && request.method === "GET")
    return json({ deviceId: device.id, deviceName: device.name, role: device.role, status: device.status });
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
