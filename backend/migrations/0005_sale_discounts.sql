-- Discount is stored separately from gross line totals for auditability.
ALTER TABLE sales
  ADD COLUMN discount_minor INTEGER NOT NULL DEFAULT 0
  CHECK (discount_minor >= 0);
