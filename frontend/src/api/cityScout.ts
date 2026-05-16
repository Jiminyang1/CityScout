import { apiRequest } from "./client";
import type {
  Shop,
  ShopType,
  UserDTO,
  Voucher,
  VoucherOrderStatus
} from "./types";

export function sendLoginCode(phone: string) {
  return apiRequest<void>(`/api/user/code?phone=${encodeURIComponent(phone)}`, {
    method: "POST"
  });
}

export function loginWithCode(phone: string, code: string) {
  return apiRequest<string>("/api/user/login", {
    method: "POST",
    body: JSON.stringify({ phone, code })
  });
}

export function logout() {
  return apiRequest<void>("/api/user/logout", { method: "POST" });
}

export function getMe() {
  return apiRequest<UserDTO>("/api/user/me");
}

export function getShopTypes() {
  return apiRequest<ShopType[]>("/api/shop-type/list");
}

export function getShopsByType(typeId: number, current = 1) {
  const params = new URLSearchParams({
    typeId: String(typeId),
    current: String(current)
  });
  return apiRequest<Shop[]>(`/api/shop/of/type?${params.toString()}`);
}

export function searchShops(name: string, current = 1) {
  const params = new URLSearchParams({
    name,
    current: String(current)
  });
  return apiRequest<Shop[]>(`/api/shop/of/name?${params.toString()}`);
}

export function getShop(shopId: string) {
  return apiRequest<Shop>(`/api/shop/${shopId}`);
}

export function getVouchers(shopId: string) {
  return apiRequest<Voucher[]>(`/api/voucher/list/${shopId}`);
}

export function seckillVoucher(voucherId: number) {
  return apiRequest<string>(`/api/voucher-order/seckill/${voucherId}`, {
    method: "POST"
  });
}

export function getMyOrders() {
  return apiRequest<VoucherOrderStatus[]>("/api/voucher-order/my");
}

export function getOrder(orderId: string) {
  return apiRequest<VoucherOrderStatus>(`/api/voucher-order/${orderId}`);
}

export function simulatePay(orderId: string) {
  return apiRequest<VoucherOrderStatus>(`/api/voucher-order/${orderId}/simulate-pay`, {
    method: "POST"
  });
}

export function cancelOrder(orderId: string) {
  return apiRequest<VoucherOrderStatus>(`/api/voucher-order/${orderId}/cancel`, {
    method: "POST"
  });
}
