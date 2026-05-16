-- Minimal shop schema and seed data for the first CityScout Web demo.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tb_shop (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  type_id BIGINT NOT NULL,
  images VARCHAR(1024) DEFAULT NULL,
  area VARCHAR(128) DEFAULT NULL,
  address VARCHAR(255) DEFAULT NULL,
  x DOUBLE DEFAULT NULL,
  y DOUBLE DEFAULT NULL,
  avg_price BIGINT DEFAULT 0,
  sold INT DEFAULT 0,
  comments INT DEFAULT 0,
  score INT DEFAULT 0,
  open_hours VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_type_id (type_id),
  KEY idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
VALUES
  (
    1,
    'CityScout Live House',
    1,
    'https://images.unsplash.com/photo-1555396273-367ea4eb4db5?auto=format&fit=crop&w=1200&q=80',
    '陆家嘴',
    '浦东新区世纪大道 88 号',
    121.506377,
    31.245105,
    12900,
    1280,
    246,
    46,
    '10:00-22:00'
  )
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  type_id = VALUES(type_id),
  images = VALUES(images),
  area = VALUES(area),
  address = VALUES(address),
  avg_price = VALUES(avg_price),
  sold = VALUES(sold),
  comments = VALUES(comments),
  score = VALUES(score),
  open_hours = VALUES(open_hours);
