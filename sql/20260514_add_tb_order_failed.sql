-- DLT 处理完成且判定彻底失败的订单留痕表
-- queryOrder 在 DB 没有订单时回退查此表，返回终态 status=-1
CREATE TABLE IF NOT EXISTS tb_order_failed (
  order_id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  voucher_id BIGINT NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  payload TEXT,
  reason VARCHAR(512),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_voucher (user_id, voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
