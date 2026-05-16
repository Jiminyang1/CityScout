import { ArrowLeft, Clock, Loader2, MapPin, ShoppingBag, Star } from "lucide-react";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { getShop, getVouchers, seckillVoucher } from "../api/cityScout";
import type { Shop, Voucher } from "../api/types";
import { formatDateTime, formatMoney, formatScore, getShopImage, parseApiDate } from "../lib/format";
import { useAuth } from "../state/auth";

export function ShopDetailPage() {
  const { shopId } = useParams<{ shopId: string }>();
  const [shop, setShop] = useState<Shop | null>(null);
  const [vouchers, setVouchers] = useState<Voucher[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [actionId, setActionId] = useState<number | null>(null);
  const [error, setError] = useState("");
  const now = useTicker(1000);
  const { isAuthenticated } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    if (!shopId) {
      return;
    }
    setIsLoading(true);
    setError("");
    Promise.all([getShop(shopId), getVouchers(shopId)])
      .then(([nextShop, nextVouchers]) => {
        setShop(nextShop);
        setVouchers(nextVouchers);
      })
      .catch((err) => setError(err instanceof Error ? err.message : "店铺加载失败"))
      .finally(() => setIsLoading(false));
  }, [shopId]);

  async function handleSeckill(voucher: Voucher) {
    if (!isAuthenticated) {
      navigate("/login", { state: { from: `/shops/${shopId}` } });
      return;
    }
    setActionId(voucher.id);
    setError("");
    try {
      const orderId = await seckillVoucher(voucher.id);
      navigate(`/orders/${orderId}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "下单失败");
    } finally {
      setActionId(null);
    }
  }

  if (isLoading) {
    return (
      <div className="loading-state page-center">
        <Loader2 size={22} className="spin" />
        正在加载店铺
      </div>
    );
  }

  if (error && !shop) {
    return <div className="empty-state error page-center">{error}</div>;
  }

  if (!shop) {
    return <div className="empty-state page-center">店铺不存在</div>;
  }

  return (
    <section className="detail-layout">
      <Link to="/shops" className="back-link inline">
        <ArrowLeft size={18} />
        返回商铺
      </Link>

      <div className="shop-detail-header">
        <img src={getShopImage(shop)} alt={shop.name} />
        <div className="shop-detail-copy">
          <p className="eyebrow">{shop.area || "CityScout 精选"}</p>
          <h1>{shop.name}</h1>
          <div className="detail-meta">
            <span>
              <Star size={16} />
              {formatScore(shop.score)}
            </span>
            <span>
              <ShoppingBag size={16} />
              {shop.sold ?? 0} 已售
            </span>
            <span>
              <Clock size={16} />
              {shop.openHours || "营业时间待补充"}
            </span>
          </div>
          <p className="shop-address large">
            <MapPin size={17} />
            {shop.address || "地址待补充"}
          </p>
        </div>
      </div>

      <div className="voucher-panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Products</p>
            <h2>店铺商品</h2>
          </div>
          <span>{vouchers.length} 张</span>
        </div>

        {error && <div className="form-message error">{error}</div>}
        {vouchers.length === 0 && <div className="empty-state">暂无可用优惠券</div>}

        <div className="voucher-list">
          {vouchers.map((voucher) => {
            const seckill = getSeckillState(voucher, now);
            const isSeckill = Boolean(seckill);
            const disabled = actionId === voucher.id || seckill?.disabled;

            return (
              <article className="voucher-card" key={voucher.id}>
                <div className={isSeckill ? "voucher-value seckill" : "voucher-value"}>
                  <span>{formatMoney(voucher.actualValue)}</span>
                  <small>{formatMoney(voucher.payValue)} {isSeckill ? "抢购" : "团购"}</small>
                </div>
                <div className="voucher-content">
                  <div className="voucher-title-row">
                    <h3>{voucher.title}</h3>
                    <span className={isSeckill ? "voucher-kind hot" : "voucher-kind"}>
                      {isSeckill ? "秒杀券" : "普通券"}
                    </span>
                  </div>
                  <p>{voucher.subTitle || voucher.rules || "到店可用，具体规则以商家说明为准。"}</p>
                  <div className="voucher-meta">
                    {isSeckill ? (
                      <>
                        <span>库存 {voucher.stock ?? "-"}</span>
                        <span>{seckill?.label} {seckill?.display}</span>
                        <span>结束 {formatDateTime(voucher.endTime)}</span>
                      </>
                    ) : (
                      <>
                        <span>无需抢购</span>
                        <span>到店可用</span>
                        <span>{voucher.rules || "不与其他优惠同享"}</span>
                      </>
                    )}
                  </div>
                </div>
                {isSeckill ? (
                  <button
                    className="primary-button"
                    onClick={() => handleSeckill(voucher)}
                    disabled={disabled}
                  >
                    {actionId === voucher.id ? "提交中" : seckill?.buttonText}
                  </button>
                ) : (
                  <button className="secondary-button" disabled>
                    普通券
                  </button>
                )}
              </article>
            );
          })}
        </div>
      </div>
    </section>
  );
}

function useTicker(intervalMs: number) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);

  return now;
}

function getSeckillState(voucher: Voucher, now: number) {
  if (!voucher.beginTime || !voucher.endTime || voucher.stock == null) {
    return null;
  }

  const begin = parseApiDate(voucher.beginTime).getTime();
  const end = parseApiDate(voucher.endTime).getTime();

  if (Number.isNaN(begin) || Number.isNaN(end)) {
    return {
      label: "限时",
      display: "--:--",
      buttonText: "立即秒杀",
      disabled: false
    };
  }

  if (now < begin) {
    return {
      label: "距开始",
      display: formatDuration(begin - now),
      buttonText: "未开始",
      disabled: true
    };
  }

  if (now >= end) {
    return {
      label: "已结束",
      display: "00:00",
      buttonText: "已结束",
      disabled: true
    };
  }

  return {
    label: "剩余",
    display: formatDuration(end - now),
    buttonText: "立即秒杀",
    disabled: voucher.stock <= 0
  };
}

function formatDuration(ms: number) {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const mm = String(minutes).padStart(2, "0");
  const ss = String(seconds).padStart(2, "0");

  if (hours > 0) {
    return `${String(hours).padStart(2, "0")}:${mm}:${ss}`;
  }
  return `${mm}:${ss}`;
}
