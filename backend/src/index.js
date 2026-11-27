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
    if (expiryDate && (!/^\d{4}-\d{2}-\d{2}$/.test(expiryDate) || !Number.isFinite(Date.parse(expiryDate)) || new Date(expiryDate).toISOString().slice(0,10) !== expiryDate))
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
        WHERE EXISTS(
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



async function customersApi(request, env, device, url, path) {
  const match = path.match(/^\/v1\/customers(?:\/([a-f0-9-]+))?$/i);
  if (!match) return null;
  const customerId = match[1] || null;
  if (request.method === "GET" && !customerId) {
    const q = validOptionalText(url.searchParams.get("q"), 100);
    const pageRaw = Number(url.searchParams.get("page") || 1);
    const page = Number.isSafeInteger(pageRaw) && pageRaw > 0 ? Math.min(pageRaw, 100000) : 1;
    const where = ["pharmacy_id=?"]; const values = [device.pharmacy_id];
    if (q) {
      where.push("(name LIKE ? OR phone LIKE ?)");
      values.push("%" + q + "%", "%" + q + "%");
    }
    const result = await env.DB.prepare(`SELECT id,name,phone,notes,created_at,updated_at
      FROM customers WHERE ${where.join(" AND ")}
      ORDER BY name COLLATE NOCASE,id LIMIT 21 OFFSET ?`)
      .bind(...values,(page-1)*20).all();
    const rows = result.results || [];
    return json({customers:rows.slice(0,20),page,pageSize:20,hasMore:rows.length>20,nextPage:rows.length>20?page+1:null});
  }
  if (request.method === "POST" && !customerId) {
    const input = await body(request);
    const name = String(input.name || "").trim().slice(0,120);
    const phone = validOptionalText(input.phone,30);
    const notes = validOptionalText(input.notes,500);
    if (!name) return json({error:"customer_name_required"},400);
    const id = crypto.randomUUID(), stamp = now();
    await env.DB.prepare("INSERT INTO customers(id,pharmacy_id,name,phone,notes,created_at,updated_at) VALUES(?,?,?,?,?,?,?)")
      .bind(id,device.pharmacy_id,name,phone,notes,stamp,stamp).run();
    await audit(env,device.pharmacy_id,device,"customer_created",id);
    return json({id,name,phone,notes},201);
  }
  if (request.method === "PUT" && customerId) {
    const input = await body(request);
    const name = String(input.name || "").trim().slice(0,120);
    if (!name) return json({error:"customer_name_required"},400);
    const phone = validOptionalText(input.phone,30), notes = validOptionalText(input.notes,500);
    const result = await env.DB.prepare("UPDATE customers SET name=?,phone=?,notes=?,updated_at=? WHERE id=? AND pharmacy_id=?")
      .bind(name,phone,notes,now(),customerId,device.pharmacy_id).run();
    if (!result.meta?.changes) return json({error:"customer_not_found"},404);
    await audit(env,device.pharmacy_id,device,"customer_updated",customerId);
    return json({id:customerId,name,phone,notes});
  }
  return json({error:"method_not_allowed"},405);
}

async function salesReadApi(request, env, device, url, path) {
  if (path === "/v1/sales" && request.method === "GET") {
    const pageRaw = Number(url.searchParams.get("page") || 1);
    const page = Number.isSafeInteger(pageRaw) && pageRaw > 0 ? Math.min(pageRaw,100000) : 1;
    const fromRaw = Number(url.searchParams.get("from") || 0);
    const toRaw = Number(url.searchParams.get("to") || 0);
    const from = Number.isSafeInteger(fromRaw) && fromRaw > 0 ? fromRaw : null;
    const to = Number.isSafeInteger(toRaw) && toRaw > 0 ? toRaw : null;
    const where = ["s.pharmacy_id=?"]; const values = [device.pharmacy_id];
    if (from !== null) { where.push("s.created_at>=?"); values.push(from); }
    if (to !== null) { where.push("s.created_at<?"); values.push(to); }
    const result = await env.DB.prepare(`SELECT s.id,s.invoice_number,s.status,s.total_minor,s.created_at,
      s.customer_id,c.name AS customer_name,
      COALESCE((SELECT SUM(p.amount_minor) FROM sale_payments p WHERE p.sale_id=s.id AND p.pharmacy_id=s.pharmacy_id),0) AS paid_minor
      FROM sales s LEFT JOIN customers c ON c.id=s.customer_id AND c.pharmacy_id=s.pharmacy_id
      WHERE ${where.join(" AND ")} ORDER BY s.created_at DESC,s.id DESC LIMIT 21 OFFSET ?`)
      .bind(...values,(page-1)*20).all();
    const rows = (result.results || []).map(row => ({...row,due_minor:Math.max(0,row.total_minor-row.paid_minor)}));
    return json({sales:rows.slice(0,20),page,pageSize:20,hasMore:rows.length>20,nextPage:rows.length>20?page+1:null});
  }
  const saleMatch = path.match(/^\/v1\/sales\/([a-f0-9-]+)$/i);
  if (saleMatch && request.method === "GET") {
    const saleId = saleMatch[1];
    const sale = await env.DB.prepare(`SELECT s.id,s.invoice_number,s.status,s.total_minor,s.created_at,s.customer_id,
      c.name AS customer_name,COALESCE((SELECT SUM(p.amount_minor) FROM sale_payments p WHERE p.sale_id=s.id AND p.pharmacy_id=s.pharmacy_id),0) AS paid_minor
      FROM sales s LEFT JOIN customers c ON c.id=s.customer_id AND c.pharmacy_id=s.pharmacy_id
      WHERE s.id=? AND s.pharmacy_id=?`).bind(saleId,device.pharmacy_id).first();
    if (!sale) return json({error:"sale_not_found"},404);
    const [items,payments] = await Promise.all([
      env.DB.prepare("SELECT id,medicine_id,batch_id,medicine_name_snapshot,quantity,unit_price_minor,line_total_minor FROM sale_items WHERE sale_id=? AND pharmacy_id=? ORDER BY id")
        .bind(saleId,device.pharmacy_id).all(),
      env.DB.prepare("SELECT id,amount_minor,payment_method,notes,created_at FROM sale_payments WHERE sale_id=? AND pharmacy_id=? ORDER BY created_at,id")
        .bind(saleId,device.pharmacy_id).all()
    ]);
    return json({...sale,due_minor:Math.max(0,sale.total_minor-sale.paid_minor),items:items.results||[],payments:payments.results||[]});
  }
  const paymentMatch = path.match(/^\/v1\/sales\/([a-f0-9-]+)\/payments$/i);
  if (paymentMatch && request.method === "POST") {
    const saleId = paymentMatch[1], input = await body(request);
    const amount = input.amountMinor, method = String(input.paymentMethod || "cash");
    const notes = validOptionalText(input.notes,250);
    if (!Number.isSafeInteger(amount) || amount <= 0 || !["cash","card","transfer","other"].includes(method))
      return json({error:"invalid_payment"},400);
    const paymentId = crypto.randomUUID(), stamp = now();
    const result = await env.DB.prepare(`INSERT INTO sale_payments(id,pharmacy_id,sale_id,customer_id,device_id,amount_minor,payment_method,notes,created_at)
      SELECT ?,s.pharmacy_id,s.id,s.customer_id,?,?,?, ?,?
      FROM sales s WHERE s.id=? AND s.pharmacy_id=? AND s.status='completed'
      AND ? <= s.total_minor-COALESCE((SELECT SUM(p.amount_minor) FROM sale_payments p WHERE p.sale_id=s.id AND p.pharmacy_id=s.pharmacy_id),0)`)
      .bind(paymentId,device.id,amount,method,notes,stamp,saleId,device.pharmacy_id,amount).run();
    if (!result.meta?.changes) return json({error:"sale_not_found_or_payment_exceeds_due"},409);
    await audit(env,device.pharmacy_id,device,"sale_payment_recorded",paymentId,{saleId,amountMinor:amount});
    return json({paymentId,saleId,amountMinor:amount,createdAt:stamp},201);
  }
  return null;
}

async function reportsApi(request, env, device, url, path) {
  if (path !== "/v1/reports/summary" || request.method !== "GET") return null;
  const from = Number(url.searchParams.get("from"));
  const to = Number(url.searchParams.get("to"));
  if (!Number.isSafeInteger(from) || !Number.isSafeInteger(to) || from < 0 || to <= from)
    return json({error:"valid_from_and_to_required"},400);
  const [summary,top] = await Promise.all([
    env.DB.prepare(`SELECT COUNT(*) AS sale_count,COALESCE(SUM(s.total_minor),0) AS revenue_minor,
      COALESCE(SUM((SELECT COALESCE(SUM(p.amount_minor),0) FROM sale_payments p WHERE p.sale_id=s.id AND p.pharmacy_id=s.pharmacy_id)),0) AS paid_minor
      FROM sales s WHERE s.pharmacy_id=? AND s.status='completed' AND s.created_at>=? AND s.created_at<?`)
      .bind(device.pharmacy_id,from,to).first(),
    env.DB.prepare(`SELECT si.medicine_id,si.medicine_name_snapshot AS medicine_name,SUM(si.quantity) AS sold_quantity,
      SUM(si.line_total_minor) AS revenue_minor FROM sale_items si JOIN sales s ON s.id=si.sale_id AND s.pharmacy_id=si.pharmacy_id
      WHERE si.pharmacy_id=? AND s.status='completed' AND s.created_at>=? AND s.created_at<?
      GROUP BY si.medicine_id,si.medicine_name_snapshot ORDER BY sold_quantity DESC,revenue_minor DESC LIMIT 10`)
      .bind(device.pharmacy_id,from,to).all()
  ]);
  const revenue = summary?.revenue_minor || 0, paid = summary?.paid_minor || 0;
  return json({from,to,saleCount:summary?.sale_count||0,revenueMinor:revenue,paidMinor:paid,dueMinor:Math.max(0,revenue-paid),topSelling:top.results||[]});
}

async function salesApi(request, env, device, path) {
  if (path !== "/v1/sales" || request.method !== "POST") return null;
  const input = await body(request);
  const operationId = String(input.operationId || "").trim();
  if (!/^[A-Za-z0-9_-]{16,80}$/.test(operationId)) return json({ error: "valid_operation_id_required" }, 400);
  if (!Array.isArray(input.items) || !input.items.length || input.items.length > 20)
    return json({ error: "sale_requires_1_to_20_items" }, 400);

  const merged = new Map();
  for (const item of input.items) {
    const id = String(item?.medicineId || "").trim(), qty = item?.quantity;
    if (!/^[a-f0-9-]{36}$/i.test(id) || !Number.isSafeInteger(qty) || qty <= 0)
      return json({ error: "invalid_sale_item" }, 400);
    merged.set(id, (merged.get(id) || 0) + qty);
    if (!Number.isSafeInteger(merged.get(id))) return json({ error: "sale_quantity_too_large" }, 400);
  }
  const lines = [...merged].map(([medicineId, quantity]) => ({ medicineId, quantity }))
    .sort((a,b) => a.medicineId.localeCompare(b.medicineId));
  const customerId = validOptionalText(input.customerId, 64);
  if (customerId && !/^[a-f0-9-]{36}$/i.test(customerId)) return json({ error: "invalid_customer_id" }, 400);
  const paid = input.paymentAmountMinor ?? 0, method = String(input.paymentMethod || "cash");
  if (!Number.isSafeInteger(paid) || paid < 0 || !["cash","card","transfer","other"].includes(method))
    return json({ error: "invalid_payment" }, 400);
  const payloadHash = await hash(JSON.stringify({ customerId, items: lines, paymentAmountMinor: paid, paymentMethod: method }));

  const findOperation = () => env.DB.prepare("SELECT payload_hash,result_json FROM sync_operations WHERE pharmacy_id=? AND operation_id=?")
    .bind(device.pharmacy_id, operationId).first();
  const prior = await findOperation();
  if (prior) {
    if (prior.payload_hash !== payloadHash) return json({ error: "operation_id_payload_mismatch" }, 409);
    return json({ ...JSON.parse(prior.result_json), replayed: true });
  }

  const ids = lines.map(x => x.medicineId), marks = ids.map(() => "?").join(",");
  const medResult = await env.DB.prepare("SELECT id,name,sale_price_minor FROM medicines WHERE pharmacy_id=? AND is_active=1 AND id IN (" + marks + ")")
    .bind(device.pharmacy_id, ...ids).all();
  const meds = new Map((medResult.results || []).map(x => [x.id,x]));
  if (meds.size !== ids.length) return json({ error: "medicine_not_found_or_inactive" }, 404);
  let total = 0;
  for (const line of lines) {
    const med = meds.get(line.medicineId), amount = med.sale_price_minor * line.quantity;
    if (!Number.isSafeInteger(amount) || !Number.isSafeInteger(total + amount))
      return json({ error: "sale_total_out_of_range" }, 400);
    Object.assign(line, { name: med.name, unitPrice: med.sale_price_minor, lineTotal: amount });
    total += amount;
  }
  if (paid > total) return json({ error: "payment_exceeds_sale_total" }, 400);
  if (customerId) {
    const customer = await env.DB.prepare("SELECT id FROM customers WHERE id=? AND pharmacy_id=?")
      .bind(customerId,device.pharmacy_id).first();
    if (!customer) return json({ error: "customer_not_found" }, 404);
  }

  const today = new Date().toISOString().slice(0,10);
  const stock = await env.DB.prepare("SELECT id,medicine_id,expiry_date,quantity FROM inventory_batches WHERE pharmacy_id=? AND medicine_id IN (" +
    marks + ") AND quantity>0 AND (expiry_date IS NULL OR expiry_date>=?) ORDER BY medicine_id,CASE WHEN expiry_date IS NULL THEN 1 ELSE 0 END,expiry_date,created_at,id")
    .bind(device.pharmacy_id,...ids,today).all();
  const byMedicine = new Map(ids.map(id => [id,[]]));
  for (const batch of stock.results || []) byMedicine.get(batch.medicine_id).push(batch);
  const allocations = [];
  for (const line of lines) {
    let left = line.quantity;
    for (const batch of byMedicine.get(line.medicineId)) {
      if (!left) break;
      const take = Math.min(left,batch.quantity);
      if (take) allocations.push({ ...batch,take,line });
      left -= take;
    }
    if (left) return json({ error:"insufficient_stock",medicineId:line.medicineId,
      requested:line.quantity,available:line.quantity-left },409);
  }
  if (!allocations.length || allocations.length > 40) return json({ error:"sale_allocation_limit_exceeded" },400);

  const saleId = crypto.randomUUID(), stamp = now(), expected = allocations.length;
  const resultBody = { saleId,invoiceNumber:saleId,totalMinor:total,paidMinor:paid,dueMinor:total-paid,
    itemCount:lines.length,createdAt:stamp };
  const allocationIds = allocations.map(x => x.id), batchMarks = allocationIds.map(() => "?").join(",");
  const caseSql = "CASE id " + allocations.map(() => "WHEN ? THEN ?").join(" ") + " ELSE 0 END";
  const caseArgs = allocations.flatMap(x => [x.id,x.take]);
  const statements = [
    env.DB.prepare("UPDATE inventory_batches SET quantity=quantity-(" + caseSql + "),updated_at=? " +
      "WHERE pharmacy_id=? AND id IN (" + batchMarks + ") AND quantity>=(" + caseSql + ") " +
      "AND (SELECT COUNT(*) FROM inventory_batches WHERE pharmacy_id=? AND id IN (" + batchMarks +
      ") AND quantity>=(" + caseSql + "))=?")
      .bind(...caseArgs,stamp,device.pharmacy_id,...allocationIds,...caseArgs,device.pharmacy_id,...allocationIds,...caseArgs,expected),
    env.DB.prepare("INSERT INTO sales(id,pharmacy_id,device_id,customer_id,invoice_number,status,total_minor,created_at,updated_at) " +
      "SELECT ?,?,?,?,?, 'completed',?,?,? WHERE changes()=?")
      .bind(saleId,device.pharmacy_id,device.id,customerId,saleId,total,stamp,stamp,expected)
  ];

  for (const a of allocations) {
    statements.push(env.DB.prepare("INSERT INTO sale_items(id,pharmacy_id,sale_id,medicine_id,batch_id,medicine_name_snapshot,quantity,unit_price_minor,line_total_minor,created_at) " +
      "SELECT ?,?,?,?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM sales WHERE id=? AND pharmacy_id=?)")
      .bind(crypto.randomUUID(),device.pharmacy_id,saleId,a.line.medicineId,a.id,a.line.name,a.take,
        a.line.unitPrice,a.take*a.line.unitPrice,stamp,saleId,device.pharmacy_id));
    statements.push(env.DB.prepare("INSERT INTO stock_movements(id,pharmacy_id,medicine_id,batch_id,device_id,movement_type,quantity_delta,reference_type,reference_id,created_at) " +
      "SELECT ?,?,?,?,?, 'sale', ?, 'sale', ?, ? WHERE EXISTS(SELECT 1 FROM sales WHERE id=? AND pharmacy_id=?)")
      .bind(crypto.randomUUID(),device.pharmacy_id,a.line.medicineId,a.id,device.id,-a.take,saleId,stamp,saleId,device.pharmacy_id));
  }
  if (paid > 0) statements.push(env.DB.prepare("INSERT INTO sale_payments(id,pharmacy_id,sale_id,customer_id,device_id,amount_minor,payment_method,created_at) " +
    "SELECT ?,?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM sales WHERE id=? AND pharmacy_id=?)")
    .bind(crypto.randomUUID(),device.pharmacy_id,saleId,customerId,device.id,paid,method,stamp,saleId,device.pharmacy_id));

  statements.push(env.DB.prepare("INSERT INTO sync_operations(pharmacy_id,operation_id,payload_hash,result_json,created_at) " +
    "SELECT ?,?,?,?,? WHERE EXISTS(SELECT 1 FROM sales WHERE id=? AND pharmacy_id=?)")
    .bind(device.pharmacy_id,operationId,payloadHash,JSON.stringify(resultBody),stamp,saleId,device.pharmacy_id));
  statements.push(env.DB.prepare("INSERT INTO audit_log(pharmacy_id,actor_device_id,action,target_id,created_at,details_json) " +
    "SELECT ?,?,'sale_created',?,?,? WHERE EXISTS(SELECT 1 FROM sales WHERE id=? AND pharmacy_id=?)")
    .bind(device.pharmacy_id,device.id,saleId,stamp,JSON.stringify({totalMinor:total,paidMinor:paid,itemCount:lines.length}),saleId,device.pharmacy_id));

  try {
    const results = await env.DB.batch(statements);
    if ((results?.[0]?.meta?.changes || 0) !== expected) {
      const raced = await findOperation();
      if (raced) {
        if (raced.payload_hash !== payloadHash) return json({error:"operation_id_payload_mismatch"},409);
        return json({...JSON.parse(raced.result_json),replayed:true});
      }
      return json({error:"insufficient_stock_or_stock_changed_retry"},409);
    }
    if (!results?.[1]?.meta?.changes) throw new Error("sale_insert_failed");
    return json(resultBody,201);
  } catch (error) {
    const raced = await findOperation();
    if (raced) {
      if (raced.payload_hash !== payloadHash) return json({error:"operation_id_payload_mismatch"},409);
      return json({...JSON.parse(raced.result_json),replayed:true});
    }
    throw error;
  }
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
  const customerResponse = await customersApi(request, env, device, url, path);
  if (customerResponse) return customerResponse;
  const salesReadResponse = await salesReadApi(request, env, device, url, path);
  if (salesReadResponse) return salesReadResponse;
  const reportsResponse = await reportsApi(request, env, device, url, path);
  if (reportsResponse) return reportsResponse;
  const saleResponse = await salesApi(request, env, device, path);
  if (saleResponse) return saleResponse;
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
