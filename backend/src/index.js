const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...corsHeaders() }
});
function corsHeaders() {
  return { "access-control-allow-origin": "*", "access-control-allow-methods": "GET,POST,PUT,DELETE,OPTIONS", "access-control-allow-headers": "authorization,content-type" };
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
  const device = await env.DB.prepare("SELECT d.id,d.name,d.role,d.status,d.pharmacy_id FROM devices d JOIN pharmacies p ON p.id=d.pharmacy_id WHERE d.token_hash=? AND d.status='active' AND p.status='active'").bind(tokenHash).first();
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

  // Fail closed unless a strong, deployment-specific salt is configured.
  const rateLimitSalt = env.RATE_LIMIT_SALT;
  if (typeof rateLimitSalt !== "string" || rateLimitSalt.length < 32) {
    return json({ error: "registration_security_not_configured" }, 503);
  }

  // Hash the Cloudflare-provided client IP; never store the raw address.
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const ipHash = await hash(ip + rateLimitSalt);
  const timestamp = now();
  // One atomic statement prevents concurrent requests from bypassing the limit.
  const limit = await env.DB.prepare(`INSERT INTO registration_limits(ip_hash,window_started_at,attempts) VALUES(?,?,1)
    ON CONFLICT(ip_hash) DO UPDATE SET
      attempts=CASE WHEN registration_limits.window_started_at<=? THEN 1 ELSE registration_limits.attempts+1 END,
      window_started_at=CASE WHEN registration_limits.window_started_at<=? THEN excluded.window_started_at ELSE registration_limits.window_started_at END
    RETURNING attempts`)
    .bind(ipHash, timestamp, timestamp - 3600, timestamp - 3600).first();
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

function normalizeMedicineName(value) {
  return String(value || "").normalize("NFKC").trim().toLocaleLowerCase().replace(/\s+/g, " ");
}
function validOptionalText(value, max) {
  if (value == null || value === "") return null;
  const text = String(value).trim();
  return text ? text.slice(0, max) : null;
}
async function medicineApi(request, env, device, url, path) {
  const match = path.match(/^\/v1\/medicines(?:\/([a-f0-9-]+))?$/i);
  if (!match) return null;
  const medicineId = match[1] || null;
  if (request.method === "GET" && !medicineId) {
    const q = normalizeMedicineName(url.searchParams.get("q") || "");
    const category = validOptionalText(url.searchParams.get("category"), 80);
    const pageValue = Number(url.searchParams.get("page") || 1);
    const page = Number.isSafeInteger(pageValue) && pageValue > 0 ? Math.min(pageValue, 100000) : 1;
    const where = ["pharmacy_id=?", "is_active=1"];
    const values = [device.pharmacy_id];
    if (q) { where.push("normalized_name LIKE ?"); values.push("%" + q + "%"); }
    if (category) { where.push("category=?"); values.push(category); }
    const result = await env.DB.prepare(`SELECT id,name,normalized_name,category,barcode,image_url,unit,sale_price_minor,low_stock_threshold,created_at,updated_at
      FROM medicines WHERE ${where.join(" AND ")}
      ORDER BY normalized_name ASC,id ASC LIMIT 21 OFFSET ?`)
      .bind(...values, (page - 1) * 20).all();
    const rows = result.results || [];
    return json({ medicines: rows.slice(0, 20), page, pageSize: 20, hasMore: rows.length > 20, nextPage: rows.length > 20 ? page + 1 : null });
  }
  if (request.method === "POST" && !medicineId) {
    const input = await body(request);
    const name = String(input.name || "").trim().slice(0, 160);
    const normalized = normalizeMedicineName(name);
    const price = input.salePriceMinor ?? 0;
    const threshold = input.lowStockThreshold ?? 0;
    if (!name || !Number.isSafeInteger(price) || price < 0 || !Number.isSafeInteger(threshold) || threshold < 0)
      return json({ error: "invalid_medicine_fields" }, 400);
    const image = validOptionalText(input.imageUrl, 2048);
    if (image) {
      try { const parsed = new URL(image); if (!["http:", "https:"].includes(parsed.protocol)) throw new Error(); }
      catch { return json({ error: "invalid_image_url" }, 400); }
    }
    const id = crypto.randomUUID(), timestamp = now();
    try {
      await env.DB.prepare(`INSERT INTO medicines
        (id,pharmacy_id,name,normalized_name,category,barcode,image_url,unit,sale_price_minor,low_stock_threshold,is_active,created_at,updated_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,1,?,?)`)
        .bind(id, device.pharmacy_id, name, normalized,
          validOptionalText(input.category, 80), validOptionalText(input.barcode, 100), image,
          validOptionalText(input.unit, 30) || "piece", price, threshold, timestamp, timestamp).run();
    } catch (error) {
      if (String(error?.message || "").includes("UNIQUE")) return json({ error: "barcode_already_exists" }, 409);
      throw error;
    }
    await audit(env, device.pharmacy_id, device, "medicine_created", id);
    return json({ id, name, normalizedName: normalized }, 201);
  }
  if (request.method === "PUT" && medicineId) {
    const input = await body(request);
    const name = String(input.name || "").trim().slice(0, 160);
    const normalized = normalizeMedicineName(name);
    const price = input.salePriceMinor;
    const threshold = input.lowStockThreshold;
    if (!name || !Number.isSafeInteger(price) || price < 0 || !Number.isSafeInteger(threshold) || threshold < 0)
      return json({ error: "invalid_medicine_fields" }, 400);
    const image = validOptionalText(input.imageUrl, 2048);
    if (image) {
      try { const parsed = new URL(image); if (!["http:", "https:"].includes(parsed.protocol)) throw new Error(); }
      catch { return json({ error: "invalid_image_url" }, 400); }
    }
    try {
      const result = await env.DB.prepare(`UPDATE medicines SET name=?,normalized_name=?,category=?,barcode=?,image_url=?,unit=?,sale_price_minor=?,low_stock_threshold=?,updated_at=?
        WHERE id=? AND pharmacy_id=? AND is_active=1`)
        .bind(name, normalized, validOptionalText(input.category, 80), validOptionalText(input.barcode, 100), image,
          validOptionalText(input.unit, 30) || "piece", price, threshold, now(), medicineId, device.pharmacy_id).run();
      if (!result.meta?.changes) return json({ error: "medicine_not_found" }, 404);
    } catch (error) {
      if (String(error?.message || "").includes("UNIQUE")) return json({ error: "barcode_already_exists" }, 409);
      throw error;
    }
    await audit(env, device.pharmacy_id, device, "medicine_updated", medicineId);
    return json({ id: medicineId, updated: true });
  }
  if (request.method === "DELETE" && medicineId) {
    const result = await env.DB.prepare("UPDATE medicines SET is_active=0,updated_at=? WHERE id=? AND pharmacy_id=? AND is_active=1")
      .bind(now(), medicineId, device.pharmacy_id).run();
    if (!result.meta?.changes) return json({ error: "medicine_not_found" }, 404);
    await audit(env, device.pharmacy_id, device, "medicine_deactivated", medicineId);
    return json({ id: medicineId, deactivated: true });
  }
  return json({ error: "method_not_allowed" }, 405);
}


async function inventoryApi(request, env, device, url, path) {
  if (path === "/v1/inventory/batches" && request.method === "GET") {
    const medicineId = validOptionalText(url.searchParams.get("medicineId"), 64);
    const pageRaw = Number(url.searchParams.get("page") || 1);
    const page = Number.isSafeInteger(pageRaw) && pageRaw > 0 ? Math.min(pageRaw, 100000) : 1;
    const where = ["b.pharmacy_id=?"];
    const values = [device.pharmacy_id];
    if (medicineId) { where.push("b.medicine_id=?"); values.push(medicineId); }
    const result = await env.DB.prepare(`SELECT b.id,b.medicine_id,m.name AS medicine_name,b.batch_number,b.expiry_date,b.quantity,b.purchase_price_minor,b.created_at,b.updated_at
      FROM inventory_batches b JOIN medicines m ON m.id=b.medicine_id AND m.pharmacy_id=b.pharmacy_id
      WHERE ${where.join(" AND ")}
      ORDER BY CASE WHEN b.expiry_date IS NULL THEN 1 ELSE 0 END,b.expiry_date ASC,b.id ASC
      LIMIT 21 OFFSET ?`).bind(...values, (page - 1) * 20).all();
    const rows = result.results || [];
    return json({ batches: rows.slice(0, 20), page, pageSize: 20, hasMore: rows.length > 20, nextPage: rows.length > 20 ? page + 1 : null });
  }

  if (path === "/v1/inventory/batches" && request.method === "POST") {
    const input = await body(request);
    const medicineId = String(input.medicineId || "").trim();
    const quantity = input.quantity;
    const purchasePrice = input.purchasePriceMinor ?? 0;
    const expiryDate = validOptionalText(input.expiryDate, 10);
    const batchNumber = validOptionalText(input.batchNumber, 80);
    if (!/^[a-f0-9-]{36}$/i.test(medicineId) ||
        !Number.isSafeInteger(quantity) || quantity <= 0 ||
        !Number.isSafeInteger(purchasePrice) || purchasePrice < 0)
      return json({ error: "invalid_batch_fields" }, 400);
    if (expiryDate && (!/^\\d{4}-\\d{2}-\\d{2}$/.test(expiryDate) || !Number.isFinite(Date.parse(expiryDate)) || new Date(expiryDate).toISOString().slice(0,10) !== expiryDate))
      return json({ error: "invalid_expiry_date" }, 400);

    const id = crypto.randomUUID(), movementId = crypto.randomUUID(), timestamp = now();
    const results = await env.DB.batch([
      env.DB.prepare(`INSERT INTO inventory_batches
        (id,pharmacy_id,medicine_id,batch_number,expiry_date,quantity,purchase_price_minor,created_at,updated_at)
        SELECT ?,?,?,?,?,?,?,?,? WHERE EXISTS(
          SELECT 1 FROM medicines WHERE id=? AND pharmacy_id=? AND is_active=1
        )`)
        .bind(id, device.pharmacy_id, medicineId, batchNumber, expiryDate, quantity, purchasePrice, timestamp, timestamp, medicineId, device.pharmacy_id),
      env.DB.prepare(`INSERT INTO stock_movements
        (id,pharmacy_id,medicine_id,batch_id,device_id,movement_type,quantity_delta,reference_type,reference_id,created_at)
        SELECT ?,?,?,?,?, 'purchase', ?, 'inventory_batch', ?, ?
        WHERE changes()=1 AND EXISTS(
          SELECT 1 FROM inventory_batches WHERE id=? AND pharmacy_id=? AND medicine_id=?
        )`)
        .bind(movementId, device.pharmacy_id, medicineId, id, device.id, quantity, id, timestamp, id, device.pharmacy_id, medicineId)
    ]);
    if (!results?.[0]?.meta?.changes || !results?.[1]?.meta?.changes)
      return json({ error: "active_medicine_not_found_or_stock_insert_failed" }, 404);
    await audit(env, device.pharmacy_id, device, "inventory_batch_created", id, { medicineId, quantity, expiryDate });
    return json({ id, medicineId, quantity, expiryDate, created: true }, 201);
  }
  return null;
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
    const rateLimitSalt = env.RATE_LIMIT_SALT;
    if (typeof rateLimitSalt !== "string" || rateLimitSalt.length < 32) {
      return json({ error: "registration_security_not_configured" }, 503);
    }
    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    const ipHash = await hash("enroll:" + ip + rateLimitSalt);
    const timestamp = now();
    const limit = await env.DB.prepare(`INSERT INTO registration_limits(ip_hash,window_started_at,attempts) VALUES(?,?,1)
      ON CONFLICT(ip_hash) DO UPDATE SET
        attempts=CASE WHEN registration_limits.window_started_at<=? THEN 1 ELSE registration_limits.attempts+1 END,
        window_started_at=CASE WHEN registration_limits.window_started_at<=? THEN excluded.window_started_at ELSE registration_limits.window_started_at END
      RETURNING attempts`)
      .bind(ipHash, timestamp, timestamp - 3600, timestamp - 3600).first();
    if ((limit?.attempts || 0) > 20) return json({ error: "enrollment_rate_limited", retryAfterSeconds: 3600 }, 429);

    const input = await body(request), code = String(input.code || "").trim().toUpperCase();
    const name = String(input.deviceName || "").trim().slice(0, 80);
    if (!/^[A-F0-9]{16}$/.test(code) || !name) return json({ error: "invalid_code_or_device_name" }, 400);
    const codeHash = await hash(code);
    const invite = await env.DB.prepare("SELECT i.code_hash,i.pharmacy_id,i.expires_at,i.used_at FROM invitations i JOIN pharmacies p ON p.id=i.pharmacy_id WHERE i.code_hash=? AND p.status='active'").bind(codeHash).first();
    if (!invite || invite.used_at || invite.expires_at < timestamp || !invite.pharmacy_id) return json({ error: "invite_invalid_or_expired" }, 410);
    const id = crypto.randomUUID();
    const results = await env.DB.batch([
      env.DB.prepare("UPDATE invitations SET used_at=? WHERE code_hash=? AND used_at IS NULL AND expires_at>=? AND pharmacy_id=? AND EXISTS(SELECT 1 FROM pharmacies WHERE id=? AND status='active')")
        .bind(timestamp, codeHash, timestamp, invite.pharmacy_id, invite.pharmacy_id),
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
    const code = randomToken(8).toUpperCase(), timestamp = now(), expiry = timestamp + 600;
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
  const medicineResponse = await medicineApi(request, env, device, url, path);
  if (medicineResponse) return medicineResponse;
  const inventoryResponse = await inventoryApi(request, env, device, url, path);
  if (inventoryResponse) return inventoryResponse;
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
