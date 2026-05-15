import { useEffect, useMemo, useRef, useState } from "react";
import {
  AlertCircle,
  CheckCircle2,
  Clock3,
  CreditCard,
  LogIn,
  LogOut,
  RefreshCw,
  Send,
  ShieldCheck,
  Ticket,
  TimerReset,
  XCircle,
  Zap,
} from "lucide-react";
import { api, clearToken, getToken, setToken } from "./api";

const FLOW_NODES = [
  {
    id: "client",
    title: "用户入口",
    meta: "React / Nginx",
    detail: "登录后点击秒杀券，前端带 authorization token 调用抢购接口。",
  },
  {
    id: "gate",
    title: "入口校验",
    meta: "Gateway + Service",
    detail: "校验活动时间、登录态和基础参数，快速拒绝无效请求。",
  },
  {
    id: "lua",
    title: "Redis Lua",
    meta: "原子预扣 + pending",
    detail: "Lua 原子判断库存、一人一单，扣库存、加用户、写 pending Hash 作为 Redis 侧 outbox。",
  },
  {
    id: "kafka",
    title: "Kafka",
    meta: "order.created (异步)",
    detail: "Lua 通过即返回订单号，Kafka 发送 fire-and-forget；失败由 reconciler 重投，彻底失败进 DLT。",
  },
  {
    id: "consumer",
    title: "订单消费者",
    meta: "幂等落库",
    detail: "消费者按 userId partition 串行处理，事务内插订单 + 扣 DB 库存；afterCommit 清理 Redis pending。",
  },
  {
    id: "mysql",
    title: "最终事实源",
    meta: "MySQL CAS",
    detail: "订单状态、库存和支付时间以 MySQL 为准，Redis 负责前置削峰。",
  },
  {
    id: "payment",
    title: "支付模拟",
    meta: "未支付 -> 已支付",
    detail: "MVP 手动触发支付成功；后端仍保留异步 PaymentSimulationService。",
  },
  {
    id: "cancel",
    title: "未支付取消",
    meta: "状态 CAS + afterCommit 释放",
    detail: "只有未支付订单能取消；DB 改状态 + 回补 stock 在事务内，Redis 释放 afterCommit 触发避免超卖。",
  },
];

const STATUS_STYLE = {
  [-1]: "cancelled",
  0: "processing",
  1: "pending",
  2: "paid",
  3: "paid",
  4: "cancelled",
  5: "pending",
  6: "cancelled",
};

const PENDING_POLL_TIMEOUT_MS = 60_000;

function App() {
  const [sessionToken, setSessionToken] = useState(getToken());
  const [phone, setPhone] = useState("13800138000");
  const [code, setCode] = useState("");
  const [user, setUser] = useState(null);
  const [shopId, setShopId] = useState("1");
  const [vouchers, setVouchers] = useState([]);
  const [orders, setOrders] = useState([]);
  const [activeOrder, setActiveOrder] = useState(null);
  const [activeNode, setActiveNode] = useState("client");
  const [toast, setToast] = useState(null);
  const toastTimer = useRef(null);
  const pollStartRef = useRef(null);
  const [loading, setLoading] = useState({
    vouchers: false,
    orders: false,
    action: "",
  });

  const selectedNode = useMemo(
    () => FLOW_NODES.find((node) => node.id === activeNode) || FLOW_NODES[0],
    [activeNode],
  );

  useEffect(() => {
    loadVouchers();
  }, []);

  useEffect(() => {
    if (!sessionToken) {
      setUser(null);
      setOrders([]);
      return;
    }
    refreshSession();
    loadOrders();
  }, [sessionToken]);

  useEffect(() => {
    if (!sessionToken || !activeOrder?.id) {
      pollStartRef.current = null;
      return undefined;
    }
    if (![0, 1].includes(activeOrder.status)) {
      pollStartRef.current = null;
      return undefined;
    }

    if (pollStartRef.current == null) {
      pollStartRef.current = Date.now();
    }

    const timer = window.setInterval(() => {
      // 仅对 status=0 (创建中) 设硬超时，status=1 是合法未支付态，由用户决定
      if (
        activeOrder.status === 0 &&
        pollStartRef.current != null &&
        Date.now() - pollStartRef.current > PENDING_POLL_TIMEOUT_MS
      ) {
        window.clearInterval(timer);
        pollStartRef.current = null;
        notify("error", "订单创建超时，请稍后在订单列表中查看");
        return;
      }
      loadOrder(activeOrder.id, false).catch(() => {});
    }, 1800);
    return () => window.clearInterval(timer);
  }, [sessionToken, activeOrder?.id, activeOrder?.status]);

  function notify(type, message) {
    setToast({ type, message });
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(null), 3200);
  }

  async function refreshSession() {
    try {
      const me = await api.me();
      setUser(me);
    } catch (error) {
      clearToken();
      setSessionToken(null);
      notify("error", error.message);
    }
  }

  async function loadVouchers(nextShopId = shopId) {
    setLoading((state) => ({ ...state, vouchers: true }));
    try {
      const data = await api.vouchers(nextShopId);
      setVouchers(Array.isArray(data) ? data : []);
    } catch (error) {
      notify("error", error.message);
    } finally {
      setLoading((state) => ({ ...state, vouchers: false }));
    }
  }

  async function loadOrders() {
    if (!getToken()) {
      return;
    }
    setLoading((state) => ({ ...state, orders: true }));
    try {
      const data = await api.myOrders();
      setOrders(Array.isArray(data) ? data : []);
    } catch (error) {
      notify("error", error.message);
    } finally {
      setLoading((state) => ({ ...state, orders: false }));
    }
  }

  async function loadOrder(orderId, showError = true) {
    const order = await api.order(orderId);
    setActiveOrder((prev) => {
      // 首次拿到非中间态时给出提示
      if (order.status === -1 && prev?.status !== -1) {
        notify("error", "订单处理失败，名额已释放");
      }
      return order;
    });
    updateNodeByStatus(order.status);
    if (![0, 1].includes(order.status)) {
      loadOrders();
    }
    return order;
  }

  async function handleSendCode() {
    setLoading((state) => ({ ...state, action: "code" }));
    try {
      await api.sendCode(phone);
      notify("success", "验证码已生成，请在后端日志里查看");
    } catch (error) {
      notify("error", error.message);
    } finally {
      setLoading((state) => ({ ...state, action: "" }));
    }
  }

  async function handleLogin(event) {
    event.preventDefault();
    setLoading((state) => ({ ...state, action: "login" }));
    try {
      const token = await api.login(phone, code);
      setToken(token);
      setSessionToken(token);
      notify("success", "登录成功");
    } catch (error) {
      notify("error", error.message);
    } finally {
      setLoading((state) => ({ ...state, action: "" }));
    }
  }

  function handleLogout() {
    clearToken();
    setSessionToken(null);
    setActiveOrder(null);
    notify("success", "已退出登录");
  }

  async function handleSeckill(voucher) {
    if (!sessionToken) {
      notify("error", "请先登录再参与秒杀");
      return;
    }
    setLoading((state) => ({ ...state, action: `seckill-${voucher.id}` }));
    setActiveNode("lua");
    try {
      const orderId = String(await api.seckill(voucher.id));
      const optimisticOrder = {
        id: orderId,
        voucherId: voucher.id,
        voucherTitle: voucher.title,
        status: 0,
        statusText: "创建中",
      };
      pollStartRef.current = Date.now();
      setActiveOrder(optimisticOrder);
      setActiveNode("kafka");
      notify("success", `抢购成功，订单号 ${orderId}，正在异步落库`);
      window.setTimeout(() => loadOrder(orderId, false).catch(() => {}), 600);
    } catch (error) {
      setActiveNode("lua");
      notify("error", error.message);
    } finally {
      setLoading((state) => ({ ...state, action: "" }));
    }
  }

  async function handleSimulatePay() {
    if (!activeOrder?.id) {
      return;
    }
    setLoading((state) => ({ ...state, action: "pay" }));
    setActiveNode("payment");
    try {
      const updated = await api.simulatePay(activeOrder.id);
      setActiveOrder(updated);
      notify("success", "模拟支付成功");
      loadOrders();
    } catch (error) {
      notify("error", error.message);
    } finally {
      setLoading((state) => ({ ...state, action: "" }));
    }
  }

  async function handleCancelOrder() {
    if (!activeOrder?.id) {
      return;
    }
    setLoading((state) => ({ ...state, action: "cancel" }));
    setActiveNode("cancel");
    try {
      const updated = await api.cancelOrder(activeOrder.id);
      setActiveOrder(updated);
      notify("success", "订单已取消，库存已回补");
      loadVouchers();
      loadOrders();
    } catch (error) {
      notify("error", error.message);
    } finally {
      setLoading((state) => ({ ...state, action: "" }));
    }
  }

  function updateNodeByStatus(status) {
    if (status === -1) {
      setActiveNode("kafka");
    } else if (status === 0) {
      setActiveNode("consumer");
    } else if (status === 1) {
      setActiveNode("mysql");
    } else if (status === 2 || status === 3) {
      setActiveNode("payment");
    } else if (status === 4 || status === 6) {
      setActiveNode("cancel");
    }
  }

  const isBusy = Boolean(loading.action);

  return (
    <div className="app-shell">
      <header className="topbar">
        <div>
          <div className="eyebrow">CityScout MVP</div>
          <h1>秒杀购票控制台</h1>
        </div>
        <div className="topbar-actions">
          <span className="system-chip">
            <Zap size={15} />
            Redis Lua
          </span>
          <span className="system-chip kafka">Kafka order.created</span>
          <span className="system-chip mysql">MySQL 事实源</span>
        </div>
      </header>

      {toast && (
        <div className={`toast ${toast.type}`}>
          {toast.type === "success" ? <CheckCircle2 size={18} /> : <AlertCircle size={18} />}
          <span>{toast.message}</span>
        </div>
      )}

      <main className="workspace">
        <section className="panel session-panel">
          <div className="panel-heading">
            <div>
              <p>SESSION</p>
              <h2>登录态</h2>
            </div>
            {sessionToken ? <ShieldCheck size={22} /> : <LogIn size={22} />}
          </div>

          {sessionToken ? (
            <div className="session-card">
              <div className="avatar">{user?.nickName?.slice(0, 1) || "U"}</div>
              <div className="session-copy">
                <strong>{user?.nickName || "已登录用户"}</strong>
                <span>ID {user?.id || "--"}</span>
              </div>
              <button className="icon-button" type="button" onClick={handleLogout} title="退出登录">
                <LogOut size={18} />
              </button>
            </div>
          ) : (
            <form className="login-form" onSubmit={handleLogin}>
              <label>
                手机号
                <input value={phone} onChange={(event) => setPhone(event.target.value)} />
              </label>
              <label>
                验证码
                <div className="inline-input">
                  <input value={code} onChange={(event) => setCode(event.target.value)} />
                  <button
                    className="icon-button"
                    type="button"
                    onClick={handleSendCode}
                    disabled={loading.action === "code"}
                    title="发送验证码"
                  >
                    <Send size={17} />
                  </button>
                </div>
              </label>
              <button className="primary-button" disabled={loading.action === "login"}>
                <LogIn size={17} />
                登录
              </button>
              <p className="hint">当前项目未接短信，验证码在后端日志中。</p>
            </form>
          )}

          <div className="shop-filter">
            <label>
              店铺 ID
              <input
                value={shopId}
                onChange={(event) => setShopId(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    loadVouchers(event.currentTarget.value);
                  }
                }}
              />
            </label>
            <button
              className="secondary-button"
              type="button"
              onClick={() => loadVouchers()}
              disabled={loading.vouchers}
            >
              <RefreshCw size={16} />
              刷新券
            </button>
          </div>
        </section>

        <section className="panel market-panel">
          <div className="panel-heading">
            <div>
              <p>SECKILL</p>
              <h2>秒杀券</h2>
            </div>
            <Ticket size={23} />
          </div>

          <div className="voucher-list">
            {vouchers.map((voucher) => {
              const disabled = isBusy || !sessionToken || !isVoucherLive(voucher);
              return (
                <article className="voucher-row" key={voucher.id}>
                  <div className="voucher-main">
                    <div className="voucher-title-line">
                      <h3>{voucher.title}</h3>
                      <span className={`stock-pill ${Number(voucher.stock) > 0 ? "ok" : "empty"}`}>
                        库存 {voucher.stock ?? "--"}
                      </span>
                    </div>
                    <p>{voucher.subTitle || voucher.rules || "秒杀活动券"}</p>
                    <div className="voucher-meta">
                      <span>{formatMoney(voucher.payValue)}</span>
                      <span>抵扣 {formatMoney(voucher.actualValue)}</span>
                      <span>{formatRange(voucher.beginTime, voucher.endTime)}</span>
                    </div>
                  </div>
                  <button
                    className="buy-button"
                    type="button"
                    disabled={disabled}
                    onClick={() => handleSeckill(voucher)}
                  >
                    <Zap size={17} />
                    {loading.action === `seckill-${voucher.id}` ? "提交中" : "抢券"}
                  </button>
                </article>
              );
            })}
            {!loading.vouchers && vouchers.length === 0 && (
              <div className="empty-state">
                <Ticket size={22} />
                <span>当前店铺暂无可展示券</span>
              </div>
            )}
          </div>
        </section>

        <section className="panel order-panel">
          <div className="panel-heading">
            <div>
              <p>ORDER</p>
              <h2>订单状态</h2>
            </div>
            <Clock3 size={23} />
          </div>

          {activeOrder ? (
            <div className="order-focus">
              <div className="order-status-line">
                <span className={`status-dot ${STATUS_STYLE[activeOrder.status] || "processing"}`} />
                <div>
                  <p>{activeOrder.voucherTitle || "秒杀券订单"}</p>
                  <strong>{activeOrder.statusText || "处理中"}</strong>
                </div>
              </div>
              <dl>
                <div>
                  <dt>订单号</dt>
                  <dd>{activeOrder.id}</dd>
                </div>
                <div>
                  <dt>创建时间</dt>
                  <dd>{formatDate(activeOrder.createTime) || "等待消费者落库"}</dd>
                </div>
                <div>
                  <dt>支付时间</dt>
                  <dd>{formatDate(activeOrder.payTime) || "--"}</dd>
                </div>
              </dl>
              <div className="order-actions">
                <button
                  className="primary-button"
                  type="button"
                  onClick={handleSimulatePay}
                  disabled={isBusy || activeOrder.status !== 1}
                >
                  <CreditCard size={17} />
                  模拟支付
                </button>
                <button
                  className="danger-button"
                  type="button"
                  onClick={handleCancelOrder}
                  disabled={isBusy || activeOrder.status !== 1}
                >
                  <XCircle size={17} />
                  取消未支付
                </button>
              </div>
            </div>
          ) : (
            <div className="empty-state tall">
              <TimerReset size={24} />
              <span>抢券后这里会实时轮询订单状态</span>
            </div>
          )}

          <div className="mini-list-header">
            <span>最近订单</span>
            <button className="text-button" type="button" onClick={loadOrders} disabled={!sessionToken}>
              刷新
            </button>
          </div>
          <div className="order-list">
            {orders.map((order) => (
              <button
                className="order-item"
                key={order.id}
                type="button"
                onClick={() => {
                  setActiveOrder(order);
                  updateNodeByStatus(order.status);
                }}
              >
                <span className={`status-dot ${STATUS_STYLE[order.status] || "processing"}`} />
                <span>{order.voucherTitle || "秒杀券"}</span>
                <strong>{order.statusText}</strong>
              </button>
            ))}
            {!loading.orders && orders.length === 0 && <p className="list-empty">暂无订单</p>}
          </div>
        </section>
      </main>

      <section className="flow-panel">
        <div className="flow-track">
          {FLOW_NODES.map((node) => (
            <button
              className={`flow-node ${node.id === activeNode ? "active" : ""}`}
              key={node.id}
              type="button"
              onClick={() => setActiveNode(node.id)}
            >
              <span>{node.title}</span>
              <small>{node.meta}</small>
            </button>
          ))}
        </div>
        <div className="flow-detail">
          <strong>{selectedNode.title}</strong>
          <p>{selectedNode.detail}</p>
        </div>
      </section>
    </div>
  );
}

function isVoucherLive(voucher) {
  const stock = Number(voucher.stock ?? 0);
  const now = Date.now();
  const begin = toDate(voucher.beginTime);
  const end = toDate(voucher.endTime);
  return stock > 0 && (!begin || begin.getTime() <= now) && (!end || end.getTime() >= now);
}

function formatMoney(value) {
  const cents = Number(value);
  if (!Number.isFinite(cents)) {
    return "¥--";
  }
  return `¥${(cents / 100).toFixed(2)}`;
}

function formatRange(begin, end) {
  const start = formatDate(begin, false);
  const finish = formatDate(end, false);
  if (!start && !finish) {
    return "长期有效";
  }
  return `${start || "现在"} - ${finish || "不限"}`;
}

function formatDate(value, withSeconds = true) {
  const date = toDate(value);
  if (!date) {
    return "";
  }
  const options = {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  };
  if (withSeconds) {
    options.second = "2-digit";
  }
  return new Intl.DateTimeFormat("zh-CN", options).format(date);
}

function toDate(value) {
  if (!value) {
    return null;
  }
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value;
    return new Date(year, month - 1, day, hour, minute, second);
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

export default App;
