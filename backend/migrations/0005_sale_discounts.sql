-- Discount is stored separately from the gross line totals for auditability.
ALTER TABLE sales
  ADD COLUMN discount_minor INTEGER NOT NULL DEFAULT 0
  CHECK (discount_minor >= 0 AND discount_minor <= total_minor);
