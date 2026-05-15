CREATE DATABASE IF NOT EXISTS hmdp
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE hmdp;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tb_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  phone VARCHAR(20) NOT NULL UNIQUE,
  password VARCHAR(128) DEFAULT NULL,
  nick_name VARCHAR(64) NOT NULL,
  icon VARCHAR(255) DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tb_user_info (
  user_id BIGINT PRIMARY KEY,
  city VARCHAR(64) DEFAULT NULL,
  introduce VARCHAR(128) DEFAULT NULL,
  fans INT NOT NULL DEFAULT 0,
  followee INT NOT NULL DEFAULT 0,
  gender TINYINT(1) DEFAULT NULL,
  birthday DATE DEFAULT NULL,
  credits INT NOT NULL DEFAULT 0,
  level TINYINT(1) NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tb_shop_type (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(32) NOT NULL,
  icon VARCHAR(255) DEFAULT '',
  sort INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_sort (sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tb_shop_type (id, name, icon, sort) VALUES
  (1, '美食', '', 1),
  (2, 'KTV',  '', 2),
  (3, '咖啡',  '', 3),
  (4, '酒店',  '', 4)
ON DUPLICATE KEY UPDATE name = VALUES(name), sort = VALUES(sort);

CREATE TABLE IF NOT EXISTS tb_voucher (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  shop_id BIGINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  sub_title VARCHAR(255) DEFAULT NULL,
  rules VARCHAR(512) DEFAULT NULL,
  pay_value BIGINT NOT NULL,
  actual_value BIGINT NOT NULL,
  type TINYINT NOT NULL DEFAULT 1,
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_shop_id (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tb_seckill_voucher (
  voucher_id BIGINT PRIMARY KEY,
  stock INT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  begin_time DATETIME NOT NULL,
  end_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tb_voucher_order (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  voucher_id BIGINT NOT NULL,
  pay_type TINYINT DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  pay_time DATETIME DEFAULT NULL,
  use_time DATETIME DEFAULT NULL,
  refund_time DATETIME DEFAULT NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  active_order_key TINYINT GENERATED ALWAYS AS (
    CASE WHEN status IN (1, 2, 3, 5) THEN 1 ELSE NULL END
  ) STORED,
  UNIQUE KEY uk_user_voucher_active (user_id, voucher_id, active_order_key),
  KEY idx_user_id (user_id),
  KEY idx_voucher_id (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

INSERT INTO tb_voucher (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES
  (1, 1, 'CityScout Live 早鸟票', 'Kafka 异步下单演示券', '限同一用户购买一张，15 分钟内完成支付', 9900, 19900, 1, 1),
  (2, 1, 'CityScout Music 秒杀票', 'Redis Lua 预扣库存演示券', '库存有限，支付前可取消并回补库存', 12900, 25900, 1, 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  sub_title = VALUES(sub_title),
  rules = VALUES(rules),
  pay_value = VALUES(pay_value),
  actual_value = VALUES(actual_value),
  status = VALUES(status);

INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time)
VALUES
  (1, 120, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 30 DAY),
  (2, 80, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 30 DAY)
ON DUPLICATE KEY UPDATE
  stock = VALUES(stock),
  begin_time = VALUES(begin_time),
  end_time = VALUES(end_time);
