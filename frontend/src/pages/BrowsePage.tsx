import { Loader2, MapPin, Search, Star, Ticket } from "lucide-react";
import { FormEvent, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { getShopTypes, getShopsByType, searchShops } from "../api/cityScout";
import type { Shop, ShopType } from "../api/types";
import { formatMoney, formatScore, getShopImage } from "../lib/format";

export function BrowsePage() {
  const [types, setTypes] = useState<ShopType[]>([]);
  const [activeType, setActiveType] = useState<number | null>(null);
  const [shops, setShops] = useState<Shop[]>([]);
  const [keyword, setKeyword] = useState("");
  const [activeKeyword, setActiveKeyword] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    getShopTypes()
      .then((items) => {
        setTypes(items);
        setActiveType(items[0]?.id ?? null);
      })
      .catch((err) => setError(err instanceof Error ? err.message : "商铺分类加载失败"));
  }, []);

  useEffect(() => {
    if (!activeType && !activeKeyword) {
      return;
    }
    setIsLoading(true);
    setError("");
    const request = activeKeyword
      ? searchShops(activeKeyword)
      : getShopsByType(activeType as number);

    request
      .then(setShops)
      .catch((err) => setError(err instanceof Error ? err.message : "商铺加载失败"))
      .finally(() => setIsLoading(false));
  }, [activeKeyword, activeType]);

  const activeTypeName = useMemo(
    () => types.find((item) => item.id === activeType)?.name,
    [activeType, types]
  );

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setActiveKeyword(keyword.trim());
  }

  function clearSearchAndSelect(typeId: number) {
    setKeyword("");
    setActiveKeyword("");
    setActiveType(typeId);
  }

  return (
    <section className="browse-layout">
      <div className="hero-band">
        <div>
          <p className="eyebrow">CityScout Web</p>
          <h1>发现本地精选店铺，抢限时秒杀券</h1>
          <p className="hero-copy">
            当前首版聚焦商铺、优惠券和订单状态闭环，适合验证后端 Redis + Kafka 秒杀链路。
          </p>
        </div>
        <form className="search-box" onSubmit={handleSearch}>
          <Search size={19} />
          <input
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="搜索商铺名称"
          />
          <button>搜索</button>
        </form>
      </div>

      <aside className="category-rail">
        <h2>分类</h2>
        <div className="category-list">
          {types.map((type) => (
            <button
              key={type.id}
              className={type.id === activeType && !activeKeyword ? "active" : ""}
              onClick={() => clearSearchAndSelect(type.id)}
            >
              {type.name}
            </button>
          ))}
        </div>
      </aside>

      <div className="shop-results">
        <div className="section-heading">
          <div>
            <p className="eyebrow">{activeKeyword ? "搜索结果" : activeTypeName || "商铺"}</p>
            <h2>{activeKeyword ? `“${activeKeyword}”相关商铺` : "推荐商铺"}</h2>
          </div>
          <span>{shops.length} 家</span>
        </div>

        {error && <div className="empty-state error">{error}</div>}
        {isLoading && (
          <div className="loading-state">
            <Loader2 size={20} className="spin" />
            正在加载商铺
          </div>
        )}
        {!isLoading && !error && shops.length === 0 && (
          <div className="empty-state">暂无商铺数据</div>
        )}

        <div className="shop-grid">
          {shops.map((shop) => (
            <Link to={`/shops/${shop.id}`} className="shop-card" key={shop.id}>
              <img src={getShopImage(shop)} alt={shop.name} />
              <div className="shop-card-body">
                <div className="shop-card-title">
                  <h3>{shop.name}</h3>
                  <span>
                    <Star size={15} />
                    {formatScore(shop.score)}
                  </span>
                </div>
                <p className="shop-address">
                  <MapPin size={15} />
                  {shop.area || "本地精选"} · {shop.address || "地址待补充"}
                </p>
                <div className="shop-metrics">
                  <span>{formatMoney(shop.avgPrice)} / 人</span>
                  <span>{shop.sold ?? 0} 已售</span>
                  <span>{shop.comments ?? 0} 评论</span>
                </div>
                <div className="ticket-hint">
                  <Ticket size={16} />
                  查看可抢优惠券
                </div>
              </div>
            </Link>
          ))}
        </div>
      </div>
    </section>
  );
}
