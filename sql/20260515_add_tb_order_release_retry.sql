-- Redis release retry outbox for canceled/refunded orders.
-- The row is inserted in the same DB transaction that changes the order status.
CREATE TABLE IF NOT EXISTS tb_order_release_retry (
  order_id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  voucher_id BIGINT NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(512) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_update_time (update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
