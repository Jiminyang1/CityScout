import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState
} from "react";
import type { ReactNode } from "react";
import { clearStoredToken, getStoredToken, storeToken } from "../api/client";
import { getMe, logout as logoutRequest } from "../api/cityScout";
import type { UserDTO } from "../api/types";

interface AuthContextValue {
  token: string | null;
  user: UserDTO | null;
  isAuthenticated: boolean;
  refreshUser: () => Promise<void>;
  login: (token: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => getStoredToken());
  const [user, setUser] = useState<UserDTO | null>(null);

  const refreshUser = useCallback(async () => {
    if (!getStoredToken()) {
      setUser(null);
      return;
    }
    const nextUser = await getMe();
    setUser(nextUser);
  }, []);

  useEffect(() => {
    if (!token) {
      setUser(null);
      return;
    }
    refreshUser().catch(() => {
      clearStoredToken();
      setToken(null);
      setUser(null);
    });
  }, [refreshUser, token]);

  const login = useCallback(
    async (nextToken: string) => {
      storeToken(nextToken);
      setToken(nextToken);
      await refreshUser();
    },
    [refreshUser]
  );

  const logout = useCallback(async () => {
    try {
      if (getStoredToken()) {
        await logoutRequest();
      }
    } finally {
      clearStoredToken();
      setToken(null);
      setUser(null);
    }
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      token,
      user,
      isAuthenticated: Boolean(token),
      refreshUser,
      login,
      logout
    }),
    [login, logout, refreshUser, token, user]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return value;
}
