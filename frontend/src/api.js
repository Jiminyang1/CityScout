const TOKEN_KEY = "cityscout_token";
const API_PREFIX = "/api";

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

async function request(path, options = {}) {
  const { method = "GET", body, auth = true } = options;
  const headers = {};

  if (body) {
    headers["Content-Type"] = "application/json";
  }
  const token = getToken();
  if (auth && token) {
    headers.authorization = token;
  }

  const response = await fetch(`${API_PREFIX}${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });

  if (response.status === 401) {
    clearToken();
    throw new Error("登录已过期，请重新登录");
  }

  let payload;
  try {
    payload = await response.json();
  } catch (error) {
    throw new Error(`HTTP ${response.status}`);
  }

  if (!response.ok || payload?.success === false) {
    throw new Error(payload?.errorMsg || `HTTP ${response.status}`);
  }
  return payload?.data ?? null;
}

export const api = {
  sendCode(phone) {
    return request(`/user/code?phone=${encodeURIComponent(phone)}`, {
      method: "POST",
      auth: false,
    });
  },
  login(phone, code) {
    return request("/user/login", {
      method: "POST",
      auth: false,
      body: { phone, code },
    });
  },
  me() {
    return request("/user/me");
  },
  vouchers(shopId) {
    return request(`/voucher/list/${shopId}`, { auth: false });
  },
  seckill(voucherId) {
    return request(`/voucher-order/seckill/${voucherId}`, { method: "POST" });
  },
  order(orderId) {
    return request(`/voucher-order/${orderId}`);
  },
  myOrders() {
    return request("/voucher-order/my");
  },
  simulatePay(orderId) {
    return request(`/voucher-order/${orderId}/simulate-pay`, { method: "POST" });
  },
  cancelOrder(orderId) {
    return request(`/voucher-order/${orderId}/cancel`, { method: "POST" });
  },
};
