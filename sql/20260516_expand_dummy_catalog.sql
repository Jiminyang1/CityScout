-- Expanded dummy catalog for the Web demo: categories, shops, normal vouchers, and seckill vouchers.
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

INSERT INTO tb_shop_type (id, name, icon, sort) VALUES
  (1, '美食', '', 1),
  (2, 'KTV',  '', 2),
  (3, '咖啡',  '', 3),
  (4, '酒店',  '', 4),
  (5, '健身', '', 5),
  (6, '展览', '', 6),
  (7, '烘焙', '', 7),
  (8, '亲子', '', 8)
ON DUPLICATE KEY UPDATE name = VALUES(name), sort = VALUES(sort);

INSERT INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
VALUES
  (1, 'CityScout Live House', 1, 'https://images.unsplash.com/photo-1555396273-367ea4eb4db5?auto=format&fit=crop&w=1200&q=80', '陆家嘴', '浦东新区世纪大道 88 号', 121.506377, 31.245105, 12900, 1280, 246, 46, '10:00-22:00'),
  (2, '梧桐花园咖啡', 3, 'https://images.unsplash.com/photo-1501339847302-ac426a4a7cbb?auto=format&fit=crop&w=1200&q=80', '衡山路', '徐汇区衡山路 20 号', 121.446601, 31.205116, 6800, 860, 118, 47, '08:30-20:30'),
  (3, 'Skyline 城景酒店', 4, 'https://images.unsplash.com/photo-1566073771259-6a8506099945?auto=format&fit=crop&w=1200&q=80', '外滩', '黄浦区中山东一路 18 号', 121.490317, 31.239663, 69900, 520, 96, 45, '00:00-24:00'),
  (4, 'Neon Room KTV', 2, 'https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?auto=format&fit=crop&w=1200&q=80', '人民广场', '黄浦区南京西路 258 号', 121.475315, 31.233817, 18800, 930, 174, 44, '12:00-02:00'),
  (5, 'Harbor Brunch Kitchen', 1, 'https://images.unsplash.com/photo-1551218808-94e220e084d2?auto=format&fit=crop&w=1200&q=80', '北外滩', '虹口区东大名路 501 号', 121.507018, 31.248155, 15800, 740, 132, 46, '09:00-21:30'),
  (6, 'CoreFit Studio', 5, 'https://images.unsplash.com/photo-1534438327276-14e5300c3a48?auto=format&fit=crop&w=1200&q=80', '静安寺', '静安区愚园路 99 号', 121.446905, 31.22331, 9900, 610, 88, 48, '07:00-22:00'),
  (7, 'Weekend Art Space', 6, 'https://images.unsplash.com/photo-1545987796-200677ee1011?auto=format&fit=crop&w=1200&q=80', '西岸', '徐汇区龙腾大道 2555 号', 121.465467, 31.178525, 8800, 420, 61, 45, '10:00-18:00'),
  (8, 'Sugar Lab Bakery', 7, 'https://images.unsplash.com/photo-1509440159596-0249088772ff?auto=format&fit=crop&w=1200&q=80', '新天地', '黄浦区马当路 159 号', 121.475362, 31.21905, 5600, 980, 156, 47, '08:00-21:00'),
  (9, 'Kids Planet 乐园', 8, 'https://images.unsplash.com/photo-1503454537195-1dcabb73ffb9?auto=format&fit=crop&w=1200&q=80', '世纪公园', '浦东新区锦绣路 1001 号', 121.55784, 31.21827, 12800, 690, 101, 44, '10:00-20:00'),
  (10, 'Steam Noodle Bar', 1, 'https://images.unsplash.com/photo-1555126634-323283e090fa?auto=format&fit=crop&w=1200&q=80', '五角场', '杨浦区政通路 189 号', 121.514529, 31.302684, 4800, 1500, 221, 43, '10:30-22:30')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  type_id = VALUES(type_id),
  images = VALUES(images),
  area = VALUES(area),
  address = VALUES(address),
  x = VALUES(x),
  y = VALUES(y),
  avg_price = VALUES(avg_price),
  sold = VALUES(sold),
  comments = VALUES(comments),
  score = VALUES(score),
  open_hours = VALUES(open_hours);

INSERT INTO tb_voucher (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES
  (1, 1, 'CityScout Live 早鸟票', 'Kafka 异步下单演示券', '限同一用户购买一张，15 分钟内完成支付', 9900, 19900, 1, 1),
  (2, 1, 'CityScout Music 秒杀票', 'Redis Lua 预扣库存演示券', '库存有限，支付前可取消并回补库存', 12900, 25900, 1, 1),
  (3, 1, '40 分钟闪抢双人票', '倒计时闪抢演示券', '每次执行 seed 后重置 40 分钟倒计时', 7900, 15800, 1, 1),
  (4, 1, 'Live House 饮品套餐', '普通券，到店使用', '无需秒杀，凭订单到店核销', 3900, 6800, 1, 1),
  (5, 2, '手冲咖啡双杯券', '普通券，到店使用', '工作日全天可用', 3600, 5800, 1, 1),
  (6, 2, '咖啡豆限量秒杀', '烘焙豆 200g 闪抢', '限同一用户购买一份', 4900, 8800, 1, 1),
  (7, 3, '城景房周末抵扣券', '普通酒店券', '需提前预约房态', 29900, 50000, 1, 1),
  (8, 4, 'KTV 黄金档欢唱券', '普通券，三小时包厢', '周五周六需提前预约', 16800, 26800, 1, 1),
  (9, 4, '午夜场秒杀包厢', 'KTV 限量闪抢', '库存有限，抢完即止', 9900, 19800, 1, 1),
  (10, 5, 'Brunch 双人套餐', '普通餐饮券', '节假日通用', 13800, 21800, 1, 1),
  (11, 6, '私教体验课', '普通健身券', '首次到店用户可用', 5900, 19900, 1, 1),
  (12, 6, '燃脂团课秒杀', '限量团课名额', '开课前 2 小时停止购买', 2900, 9900, 1, 1),
  (13, 7, '双人展览门票', '普通展览券', '闭馆前 1 小时停止入场', 6800, 12000, 1, 1),
  (14, 8, '招牌可颂礼盒', '普通烘焙券', '每日 10:00 后可提货', 5200, 8800, 1, 1),
  (15, 8, '晚间面包秒杀袋', '当日限定惊喜袋', '售出不退，建议当日食用', 1990, 5900, 1, 1),
  (16, 9, '亲子乐园全天票', '普通亲子券', '一大一小入场', 9900, 15800, 1, 1),
  (17, 10, '招牌牛肉面套餐', '普通餐饮券', '堂食外带均可', 2990, 4800, 1, 1)
ON DUPLICATE KEY UPDATE
  shop_id = VALUES(shop_id),
  title = VALUES(title),
  sub_title = VALUES(sub_title),
  rules = VALUES(rules),
  pay_value = VALUES(pay_value),
  actual_value = VALUES(actual_value),
  status = VALUES(status);

INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time)
VALUES
  (1, 120, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 30 DAY),
  (2, 80, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 30 DAY),
  (3, 40, NOW() - INTERVAL 5 SECOND, NOW() + INTERVAL 40 MINUTE),
  (6, 35, NOW() - INTERVAL 10 MINUTE, NOW() + INTERVAL 2 DAY),
  (9, 24, NOW() - INTERVAL 10 MINUTE, NOW() + INTERVAL 1 DAY),
  (12, 30, NOW() - INTERVAL 10 MINUTE, NOW() + INTERVAL 3 DAY),
  (15, 50, NOW() - INTERVAL 10 MINUTE, NOW() + INTERVAL 8 HOUR)
ON DUPLICATE KEY UPDATE
  stock = VALUES(stock),
  begin_time = VALUES(begin_time),
  end_time = VALUES(end_time);
