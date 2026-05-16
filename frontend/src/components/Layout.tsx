import {
  Compass,
  LogOut,
  ReceiptText,
  Store,
  UserRound
} from "lucide-react";
import { Link, NavLink, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../state/auth";

export function Layout() {
  const { isAuthenticated, user, logout } = useAuth();
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    navigate("/");
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <Link to="/" className="brand" aria-label="CityScout 首页">
          <span className="brand-mark">
            <Compass size={22} />
          </span>
          <span>
            <strong>CityScout</strong>
            <small>本地精选与秒杀券</small>
          </span>
        </Link>

        <nav className="nav-links" aria-label="主导航">
          <NavLink to="/shops" className={({ isActive }) => (isActive ? "active" : "")}>
            <Store size={18} />
            商铺
          </NavLink>
          <NavLink to="/orders" className={({ isActive }) => (isActive ? "active" : "")}>
            <ReceiptText size={18} />
            订单
          </NavLink>
        </nav>

        <div className="topbar-actions">
          {isAuthenticated ? (
            <>
              <div className="user-chip">
                <UserRound size={17} />
                {user?.nickName || "已登录"}
              </div>
              <button className="icon-button" onClick={handleLogout} aria-label="退出登录">
                <LogOut size={18} />
              </button>
            </>
          ) : (
            <Link className="primary-link" to="/login">
              登录
            </Link>
          )}
        </div>
      </header>

      <main className="main-frame">
        <Outlet />
      </main>
    </div>
  );
}
