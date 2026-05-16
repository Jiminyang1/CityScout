import type { ApiResult } from "./types";

const TOKEN_KEY = "cityscout.token";

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status?: number
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export function getStoredToken() {
  return window.localStorage.getItem(TOKEN_KEY);
}

export function storeToken(token: string) {
  window.localStorage.setItem(TOKEN_KEY, token);
}

export function clearStoredToken() {
  window.localStorage.removeItem(TOKEN_KEY);
}

export async function apiRequest<T>(
  path: string,
  init: RequestInit = {}
): Promise<T> {
  const token = getStoredToken();
  const headers = new Headers(init.headers);
  const hasBody = init.body !== undefined;

  if (hasBody && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("authorization", token);
  }

  const response = await fetch(path, {
    ...init,
    headers
  });

  let payload: ApiResult<T> | undefined;
  try {
    payload = (await response.json()) as ApiResult<T>;
  } catch {
    payload = undefined;
  }

  if (!response.ok) {
    throw new ApiError(payload?.errorMsg || `请求失败 (${response.status})`, response.status);
  }

  if (!payload) {
    throw new ApiError("服务返回格式异常", response.status);
  }

  if (!payload.success) {
    throw new ApiError(payload.errorMsg || "请求失败", response.status);
  }

  return payload.data as T;
}
