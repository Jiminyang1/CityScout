-- usage example:
-- mysql -h localhost -P 3306 -uroot -p123456 hmdp \
--   --execute="SET @voucher_id=900001; SOURCE benchmarks/scripts/audit.sql;"

SELECT COUNT(*) AS duplicate_users
FROM (
  SELECT user_id
  FROM tb_voucher_order
  WHERE voucher_id = @voucher_id
  GROUP BY user_id
  HAVING COUNT(*) > 1
) t;

SELECT COUNT(*) AS sold_count
FROM tb_voucher_order
WHERE voucher_id = @voucher_id;
