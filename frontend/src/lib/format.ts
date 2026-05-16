import type { Shop } from "../api/types";

export const FALLBACK_SHOP_IMAGE =
  "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?auto=format&fit=crop&w=1200&q=80";

export function formatMoney(value?: number) {
  if (value == null) {
    return "-";
  }
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
    minimumFractionDigits: 0,
    maximumFractionDigits: 2
  }).format(value / 100);
}

export function formatScore(score?: number) {
  if (score == null || score <= 0) {
    return "暂无评分";
  }
  return (score / 10).toFixed(1);
}

export function formatDateTime(value?: string) {
  if (!value) {
    return "-";
  }
  const date = parseApiDate(value);
  if (Number.isNaN(date.getTime())) {
    return value.replace("T", " ");
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }).format(date);
}

export function getShopImage(shop?: Pick<Shop, "images">) {
  const raw = shop?.images?.split(",").find(Boolean)?.trim();
  return raw || FALLBACK_SHOP_IMAGE;
}

export function parseApiDate(value: string) {
  if (/[zZ]|[+-]\d{2}:\d{2}$/.test(value)) {
    return new Date(value);
  }
  return new Date(`${value}Z`);
}
