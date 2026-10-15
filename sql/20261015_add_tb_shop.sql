-- Shop table and demo shops. tb_voucher.shop_id = 1 refers to shop 1.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tb_shop (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  type_id BIGINT NOT NULL,
  images VARCHAR(1024) NOT NULL DEFAULT '',
  area VARCHAR(128) DEFAULT NULL,
  address VARCHAR(255) NOT NULL,
  x DOUBLE NOT NULL,
  y DOUBLE NOT NULL,
  avg_price BIGINT DEFAULT NULL,
  sold INT NOT NULL DEFAULT 0,
  comments INT NOT NULL DEFAULT 0,
  score INT NOT NULL DEFAULT 0,
  open_hours VARCHAR(32) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_type_id (type_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
VALUES
  (1, 'CityScout 音乐现场', 2, '', '静安寺', '上海市静安区南京西路 1699 号', 121.4456, 31.2234, 180, 2316, 418, 47, '19:00-02:00'),
  (2, '弄堂小馆', 1, '', '新天地', '上海市黄浦区马当路 245 号', 121.4737, 31.2186, 95, 5120, 1204, 46, '10:30-22:00'),
  (3, '人民广场生煎铺', 1, '', '人民广场', '上海市黄浦区黄河路 97 号', 121.4752, 31.2317, 35, 8840, 2630, 45, '07:00-20:00'),
  (4, '梧桐咖啡', 3, '', '武康路', '上海市徐汇区武康路 378 号', 121.4381, 31.2071, 48, 3302, 760, 48, '08:00-21:00'),
  (5, '外滩江景酒店', 4, '', '外滩', '上海市黄浦区中山东一路 20 号', 121.4900, 31.2397, 1280, 640, 212, 47, '00:00-24:00')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  type_id = VALUES(type_id),
  area = VALUES(area),
  address = VALUES(address),
  x = VALUES(x),
  y = VALUES(y),
  avg_price = VALUES(avg_price),
  open_hours = VALUES(open_hours);
