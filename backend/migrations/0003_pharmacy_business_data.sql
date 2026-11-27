-- Pharmacy business data: tenant-scoped catalog, stock, sales and customer ledger.
PRAGMA foreign_keys = ON;

-- Composite parent key lets SQLite enforce device ownership in tenant-scoped foreign keys.
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_id_pharmacy
  ON devices(id, pharmacy_id);

CREATE TABLE IF NOT EXISTS medicines (
  id TEXT PRIMARY KEY,
  pharmacy_id TEXT NOT NULL REFERENCES pharmacies(id),
  name TEXT NOT NULL,
  normalized_name TEXT NOT NULL,
  category TEXT,
  barcode TEXT,
  image_url TEXT,
  unit TEXT NOT NULL DEFAULT 'piece',
  sale_price_minor INTEGER NOT NULL DEFAULT 0 CHECK (sale_price_minor >= 0),
  low_stock_threshold INTEGER NOT NULL DEFAULT 0 CHECK (low_stock_threshold >= 0),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(id, pharmacy_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_medicines_pharmacy_barcode
  ON medicines(pharmacy_id, barcode) WHERE barcode IS NOT NULL AND barcode <> '';
CREATE INDEX IF NOT EXISTS idx_medicines_pharmacy_name
  ON medicines(pharmacy_id, normalized_name, id);
CREATE INDEX IF NOT EXISTS idx_medicines_pharmacy_category
  ON medicines(pharmacy_id, category, name);

CREATE TABLE IF NOT EXISTS inventory_batches (
  id TEXT PRIMARY KEY,
  pharmacy_id TEXT NOT NULL,
  medicine_id TEXT NOT NULL,
  batch_number TEXT,
  expiry_date TEXT,
  quantity INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
  purchase_price_minor INTEGER NOT NULL DEFAULT 0 CHECK (purchase_price_minor >= 0),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY (medicine_id, pharmacy_id) REFERENCES medicines(id, pharmacy_id),
  UNIQUE(id, pharmacy_id),
  UNIQUE(id, pharmacy_id, medicine_id)
);
CREATE INDEX IF NOT EXISTS idx_batches_pharmacy_medicine_expiry
  ON inventory_batches(pharmacy_id, medicine_id, expiry_date, id);
CREATE INDEX IF NOT EXISTS idx_batches_pharmacy_expiry
  ON inventory_batches(pharmacy_id, expiry_date, quantity);

CREATE TABLE IF NOT EXISTS customers (
  id TEXT PRIMARY KEY,
  pharmacy_id TEXT NOT NULL REFERENCES pharmacies(id),
  name TEXT NOT NULL,
  phone TEXT,
  notes TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(id, pharmacy_id)
);
CREATE INDEX IF NOT EXISTS idx_customers_pharmacy_name
  ON customers(pharmacy_id, name, id);
CREATE INDEX IF NOT EXISTS idx_customers_pharmacy_phone
  ON customers(pharmacy_id, phone);

CREATE TABLE IF NOT EXISTS sales (
  id TEXT PRIMARY KEY,
  pharmacy_id TEXT NOT NULL REFERENCES pharmacies(id),
  device_id TEXT REFERENCES devices(id),
  customer_id TEXT,
  invoice_number TEXT,
  status TEXT NOT NULL DEFAULT 'completed' CHECK (status IN ('completed','void')),
  total_minor INTEGER NOT NULL CHECK (total_minor >= 0),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(id, pharmacy_id),
  FOREIGN KEY (customer_id, pharmacy_id) REFERENCES customers(id, pharmacy_id),
  FOREIGN KEY (device_id, pharmacy_id) REFERENCES devices(id, pharmacy_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sales_pharmacy_invoice
  ON sales(pharmacy_id, invoice_number) WHERE invoice_number IS NOT NULL AND invoice_number <> '';
CREATE INDEX IF NOT EXISTS idx_sales_pharmacy_created
  ON sales(pharmacy_id, created_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_sales_pharmacy_customer
  ON sales(pharmacy_id, customer_id, created_at DESC);

CREATE TABLE IF NOT EXISTS sale_items (
  id TEXT PRIMARY KEY,
  pharmacy_id TEXT NOT NULL,
  sale_id TEXT NOT NULL,
  medicine_id TEXT NOT NULL,
  batch_id TEXT NOT NULL,
  medicine_name_snapshot TEXT NOT NULL,
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  unit_price_minor INTEGER NOT NULL CHECK (unit_price_minor >= 0),
  line_total_minor INTEGER NOT NULL CHECK (line_total_minor >= 0),
  created_at INTEGER NOT NULL,
  FOREIGN KEY (sale_id, pharmacy_id) REFERENCES sales(id, pharmacy_id),
  FOREIGN KEY (medicine_id, pharmacy_id) REFERENCES medicines(id, pharmacy_id),
  FOREIGN KEY (batch_id, pharmacy_id, medicine_id) REFERENCES inventory_batches(id, pharmacy_id, medicine_id)
);
CREATE INDEX IF NOT EXISTS idx_sale_items_pharmacy_sale
  ON sale_items(pharmacy_id, sale_id);
CREATE INDEX IF NOT EXISTS idx_sale_items_pharmacy_medicine
  ON sale_items(pharmacy_id, medicine_id, created_at);

CREATE TABLE IF NOT EXISTS stock_movements (
  id TEXT PRIMARY KEY,
  pharmacy_id TEXT NOT NULL,
  medicine_id TEXT NOT NULL,
  batch_id TEXT,
  device_id TEXT,
  movement_type TEXT NOT NULL CHECK (movement_type IN ('purchase','sale','adjustment','return','expired','void_reversal')),
  quantity_delta INTEGER NOT NULL CHECK (quantity_delta <> 0),
  reference_type TEXT,
  reference_id TEXT,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (medicine_id, pharmacy_id) REFERENCES medicines(id, pharmacy_id),
  FOREIGN KEY (batch_id, pharmacy_id, medicine_id) REFERENCES inventory_batches(id, pharmacy_id, medicine_id),
  FOREIGN KEY (device_id, pharmacy_id) REFERENCES devices(id, pharmacy_id)
);
CREATE INDEX IF NOT EXISTS idx_stock_movements_pharmacy_medicine_time
  ON stock_movements(pharmacy_id, medicine_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stock_movements_pharmacy_batch
  ON stock_movements(pharmacy_id, batch_id, created_at DESC);

CREATE TABLE IF NOT EXISTS sale_payments (
  id TEXT PRIMARY KEY,
  pharmacy_id TEXT NOT NULL,
  sale_id TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  device_id TEXT,
  amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
  payment_method TEXT NOT NULL DEFAULT 'cash',
  notes TEXT,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (sale_id, pharmacy_id) REFERENCES sales(id, pharmacy_id),
  FOREIGN KEY (customer_id, pharmacy_id) REFERENCES customers(id, pharmacy_id),
  FOREIGN KEY (device_id, pharmacy_id) REFERENCES devices(id, pharmacy_id)
);
CREATE INDEX IF NOT EXISTS idx_sale_payments_pharmacy_sale
  ON sale_payments(pharmacy_id, sale_id, created_at);
CREATE INDEX IF NOT EXISTS idx_sale_payments_pharmacy_customer
  ON sale_payments(pharmacy_id, customer_id, created_at DESC);

-- Idempotency ledger prevents a retried offline sync request from applying twice.
CREATE TABLE IF NOT EXISTS sync_operations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  pharmacy_id TEXT NOT NULL REFERENCES pharmacies(id),
  operation_id TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  result_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(pharmacy_id, operation_id)
);
CREATE INDEX IF NOT EXISTS idx_sync_operations_pharmacy_created
  ON sync_operations(pharmacy_id, created_at DESC);
