
CREATE TABLE sale_payments_v2 (
  id TEXT PRIMARY KEY,
  pharmacy_id TEXT NOT NULL,
  sale_id TEXT NOT NULL,
  customer_id TEXT,
  device_id TEXT,
  amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
  payment_method TEXT NOT NULL DEFAULT 'cash',
  notes TEXT,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (sale_id, pharmacy_id) REFERENCES sales(id, pharmacy_id),
  FOREIGN KEY (customer_id, pharmacy_id) REFERENCES customers(id, pharmacy_id),
  FOREIGN KEY (device_id, pharmacy_id) REFERENCES devices(id, pharmacy_id)
);

INSERT INTO sale_payments_v2 (
  id, pharmacy_id, sale_id, customer_id, device_id,
  amount_minor, payment_method, notes, created_at
)
SELECT
  id, pharmacy_id, sale_id, customer_id, device_id,
  amount_minor, payment_method, notes, created_at
FROM sale_payments;

DROP TABLE sale_payments;
ALTER TABLE sale_payments_v2 RENAME TO sale_payments;

CREATE INDEX idx_sale_payments_pharmacy_sale
  ON sale_payments(pharmacy_id, sale_id, created_at);
CREATE INDEX idx_sale_payments_pharmacy_customer
  ON sale_payments(pharmacy_id, customer_id, created_at DESC);
