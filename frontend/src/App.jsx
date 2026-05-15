import { useEffect, useMemo, useRef, useState } from "react";
import {
  Activity,
  AlertCircle,
  ArrowRight,
  CheckCircle2,
  CircleDot,
  Clock3,
  CreditCard,
  Database,
  Gauge,
  KeyRound,
  Layers3,
  ListRestart,
  Loader2,
  LogIn,
  LogOut,
  RadioTower,
  ReceiptText,
  RefreshCw,
  Send,
  ServerCog,
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
    title: "Client",
    label: "用户入口",
    meta: "React + Nginx",
    detail: "登录态存在本地 token，所有受保护请求带 authorization header。",
  },
  {
    id: "gate",
    title: "Gateway",
    label: "入口校验",
    meta: "Spring MVC",
    detail: "拦截器恢复 UserHolder，业务层校验活动时间、登录态和请求参数。",
  },
  {
    id: "lua",
    title: "Redis Lua",
    label: "原子预扣",
    meta: "stock / order / pending",
    detail: "Lua 一次完成库存判断、一人一单、库存预扣和 pending outbox 写入。",
  },
  {
    id: "kafka",
    title: "Kafka",
    label: "事件投递",
    meta: "order.created",
    detail: "接口快速返回订单号；Kafka 失败时保留 Redis pending，reconciler 负责重投。",
  },
  {
    id: "consumer",
    title: "Consumer",
    label: "异步落库",
    meta: "userId partition",
    detail: "消费者按用户分区串行处理，事务内创建订单并扣 DB 库存。",
  },
  {
    id: "mysql",
    title: "MySQL",
    label: "事实源",
    meta: "CAS status",
    detail: "订单状态和库存以 MySQL 为最终事实，Redis 只做前置削峰和补偿索引。",
  },
  {
    id: "payment",
    title: "Payment",
    label: "支付确认",
    meta: "manual simulation",
    detail: "MVP 通过按钮模拟支付成功，只允许未支付订单进入已支付状态。",
  },
  {
    id: "cancel",
    title: "Release",
    label: "取消释放",
    meta: "afterCommit + retry",
    detail: "取消先提交 DB 状态和库存，afterCommit 再释放 Redis 用户占位，失败由 release retry 表补偿。",
  },
];

const STATUS_META = {
  [-1]: {
    tone: "failed",
    label: "下单失败",
    stage: "kafka",
    copy: "DLT 或兜底逻辑已判定失败，Redis 名额应已释放。",
  },
  0: {
    tone: "processing",
    label: "创建中",
    stage: "consumer",
    copy: "订单号已返回，正在等待 Kafka 消费和 DB 落库。",
  },
  1: {
    tone: "pending",
    label: "未支付",
    stage: "mysql",
    copy: "订单已落库，可以模拟支付或取消未支付订单。",
  },
  2: {
    tone: "paid",
    label: "已支付",
    stage: "payment",
    copy: "支付状态已提交，后续可进入核销链路。",
  },
  3: {
    tone: "paid",
    label: "已核销",
    stage: "payment",
    copy: "订单已完成核销。",
  },
  4: {
    tone: "cancelled",
    label: "已取消",
    stage: "cancel",
    copy: "DB 已取消并回补库存，Redis 释放由 afterCommit 和 retry 保障。",
  },
  5: {
    tone: "pending",
    label: "退款中",
    stage: "payment",
    copy: "退款流程处理中。",
  },
  6: {
    tone: "cancelled",
    label: "已退款",
    stage: "cancel",
    copy: "订单已完成退款。",
  },
};

const SCENARIOS = [
  "登录后刷新店铺券",
  "点击抢券观察订单从创建中到未支付",
  "模拟支付验证状态 CAS",
  "取消未支付验证 afterCommit 释放",
  "重复抢同一券验证一人一单",
];

const PENDING_POLL_TIMEOUT_MS = 60_000;
const EVENT_LIMIT = 12;

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
  const [events, setEvents] = useState([]);
  const [lastSyncAt, setLastSyncAt] = useState(null);
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

  const orderMeta = activeOrder
    ? STATUS_META[activeOrder.status] || {
        tone: "processing",
        label: activeOrder.statusText || "处理中",
        stage: "consumer",
        copy: "订单状态正在刷新。",
      }
    : null;

  const liveVoucherCount = useMemo(
    () => vouchers.filter((voucher) => isVoucherLive(voucher)).length,
    [vouchers],
  );

  const stockTotal = useMemo(
    () =>
      vouchers.reduce((sum, voucher) => {
        const stock = Number(voucher.stock);
        return sum + (Number.isFinite(stock) && stock > 0 ? stock : 0);
      }, 0),
    [vouchers],
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
      if (
        activeOrder.status === 0 &&
        pollStartRef.current != null &&
        Date.now() - pollStartRef.current > PENDING_POLL_TIMEOUT_MS
      ) {
        window.clearInterval(timer);
        pollStartRef.current = null;
        notify("error", "订单创建超时，请稍后刷新订单列表");
        pushEvent("failed", "轮询超时", `orderId=${activeOrder.id}`);
        return;
      }
      loadOrder(activeOrder.id, false).catch(() => {});
    }, 1800);
    return () => window.clearInterval(timer);
  }, [sessionToken, activeOrder?.id, activeOrder?.status]);

  function pushEvent(type, title, detail) {
    setEvents((prev) => [
      {
        id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
        type,
        title,
        detail,
        time: new Date(),
      },
      ...prev,
    ].slice(0, EVENT_LIMIT));
  }

  function notify(type, message) {
    setToast({ type, message });
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(null), 3200);
  }

  async function refreshSession() {
    try {
      const me = await api.me();
      setUser(me);
      pushEvent("success", "会话刷新", me?.nickName || `userId=${me?.id || "--"}`);
    } catch (error) {
      clearToken();
      setSessionToken(null);
      notify("error", error.message);
      pushEvent("failed", "会话失效", error.message);
    }
  }

  async function loadVouchers(nextShopId = shopId) {
    setLoading((state) => ({ ...state, vouchers: true }));
    try {
      const data = await api.vouchers(nextShopId);
      const nextVouchers = Array.isArray(data) ? data : [];
      setVouchers(nextVouchers);
      setLastSyncAt(new Date());
      pushEvent("success", "券列表刷新", `shopId=${nextShopId}, count=${nextVouchers.length}`);
    } catch (error) {
      notify("error", error.message);
      pushEvent("failed", "券列表失败", error.message);
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
      const nextOrders = Array.isArray(data) ? data : [];
      setOrders(nextOrders);
      setLastSyncAt(new Date());
    } catch (error) {
      notify("error", error.message);
      pushEvent("failed", "订单列表失败", error.message);
    } finally {
      setLoading((state) => ({ ...state, orders: false }));
    }
  }

  async function loadOrder(orderId, showStatusEvent = true) {
    const beforeStatus = activeOrder?.status;
    const order = await api.order(orderId);
    setActiveOrder(order);
    updateNodeByStatus(order.status);
    setLastSyncAt(new Date());
    if (order.status !== beforeStatus) {
      pushEvent(
        order.status === -1 ? "failed" : "success",
        "订单状态变更",
        `${order.id} -> ${order.statusText || STATUS_META[order.status]?.label || order.status}`,
      );
    } else if (showStatusEvent) {
      pushEvent("info", "订单状态刷新", `${order.id} -> ${order.statusText || order.status}`);
    }
    if (![0, 1].includes(order.status)) {
      loadOrders();
    }
    return order;
  }

  async function handleSendCode() {
    setLoading((state) => ({ ...state, action: "code" }));
    try {
      await api.sendCode(phone);
      notify("success", "验证码已生成，请查看后端日志");
      pushEvent("success", "验证码请求", phone);
    } catch (error) {
      notify("error", error.message);
      pushEvent("failed", "验证码失败", error.message);
    } finally {
      setLoading((state) => ({ ...state, action: "" }));
    }
  }

  async function handleLogin(event) {
    event.preventDefault();
    setLoading((state) => ({ ...state, action: "login" }));
    setActiveNode("gate");
    try {
      const token = await api.login(phone, code);
      setToken(token);
      setSessionToken(token);
      notify("success", "登录成功");
      pushEvent("success", "登录成功", maskToken(token));
    } catch (error) {
      notify("error", error.message);
      pushEvent("failed", "登录失败", error.message);
    } finally {
      setLoading((state) => ({ ...state, action: "" }));
    }
  }

  function handleLogout() {
    clearToken();
    setSessionToken(null);
    setActiveOrder(null);
    setActiveNode("client");
    notify("success", "已退出登录");
    pushEvent("info", "退出登录", "local token cleared");
  }

  async function handleSeckill(voucher) {
    if (!sessionToken) {
      notify("error", "请先登录再参与秒杀");
      return;
    }
    setLoading((state) => ({ ...state, action: `seckill-${voucher.id}` }));
    setActiveNode("lua");
    pushEvent("info", "提交秒杀", `${voucher.title || "voucher"} #${voucher.id}`);
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
      notify("success", `订单号 ${orderId} 已返回`);
      pushEvent("success", "Lua 预扣成功", `orderId=${orderId}, waiting kafka`);
      window.setTimeout(() => loadOrder(orderId, false).catch(() => {}), 600);
    } catch (error) {
      setActiveNode("lua");
      notify("error", error.message);
      pushEvent("failed", "秒杀失败", error.message);
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
      pushEvent("success", "支付提交", `${updated.id} -> ${updated.statusText}`);
      loadOrders();
    } catch (error) {
      notify("error", error.message);
      pushEvent("failed", "支付失败", error.message);
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
      pushEvent("success", "取消提交", `${updated.id} -> afterCommit release`);
      loadVouchers();
      loadOrders();
    } catch (error) {
      notify("error", error.message);
      pushEvent("failed", "取消失败", error.message);
    } finally {
      setLoading((state) => ({ ...state, action: "" }));
    }
  }

  function updateNodeByStatus(status) {
    setActiveNode(STATUS_META[status]?.stage || "consumer");
  }

  const isBusy = Boolean(loading.action);

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand-block">
          <div className="brand-mark">
            <Zap size={22} />
          </div>
          <div>
            <p className="eyebrow">CityScout Seckill Lab</p>
            <h1>架构验证台</h1>
          </div>
        </div>

        <div className="runtime-strip" aria-label="runtime stack">
          <span>
            <ServerCog size={15} />
            Spring Boot 3
          </span>
          <span>
            <Activity size={15} />
            Redis Lua
          </span>
          <span>
            <RadioTower size={15} />
            Kafka
          </span>
          <span>
            <Database size={15} />
            MySQL
          </span>
        </div>
      </header>

      {toast && (
        <div className={`toast ${toast.type}`}>
          {toast.type === "success" ? <CheckCircle2 size={18} /> : <AlertCircle size={18} />}
          <span>{toast.message}</span>
        </div>
      )}

      <section className="signal-grid" aria-label="current test signals">
        <Metric
          icon={<ShieldCheck size={18} />}
          label="Session"
          value={sessionToken ? "AUTH" : "GUEST"}
          tone={sessionToken ? "good" : "muted"}
        />
        <Metric icon={<Ticket size={18} />} label="Live vouchers" value={liveVoucherCount} tone="blue" />
        <Metric icon={<Gauge size={18} />} label="Redis stock view" value={stockTotal} tone="amber" />
        <Metric
          icon={<Clock3 size={18} />}
          label="Last sync"
          value={lastSyncAt ? formatClock(lastSyncAt) : "--"}
          tone="muted"
        />
      </section>

      <main className="workbench">
        <aside className="control-column">
          <section className="surface">
            <div className="surface-heading">
              <div>
                <p>SESSION</p>
                <h2>测试身份</h2>
              </div>
              {sessionToken ? <ShieldCheck size={22} /> : <KeyRound size={22} />}
            </div>

            {sessionToken ? (
              <div className="identity-row">
                <div className="avatar">{user?.nickName?.slice(0, 1) || "U"}</div>
                <div className="identity-copy">
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
                      {loading.action === "code" ? <Loader2 size={17} /> : <Send size={17} />}
                    </button>
                  </div>
                </label>
                <button className="primary-button" disabled={loading.action === "login"}>
                  {loading.action === "login" ? <Loader2 size={17} /> : <LogIn size={17} />}
                  登录
                </button>
              </form>
            )}
          </section>

          <section className="surface compact-surface">
            <div className="surface-heading">
              <div>
                <p>CONTEXT</p>
                <h2>店铺与刷新</h2>
              </div>
              <RefreshCw size={21} />
            </div>

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
              <div className="quick-shop-row">
                {["1", "2", "3"].map((id) => (
                  <button
                    className={shopId === id ? "segment active" : "segment"}
                    key={id}
                    type="button"
                    onClick={() => {
                      setShopId(id);
                      loadVouchers(id);
                    }}
                  >
                    {id}
                  </button>
                ))}
              </div>
              <button
                className="secondary-button"
                type="button"
                onClick={() => loadVouchers()}
                disabled={loading.vouchers}
              >
                {loading.vouchers ? <Loader2 size={16} /> : <RefreshCw size={16} />}
                刷新券
              </button>
            </div>
          </section>
        </aside>

        <section className="surface voucher-surface">
          <div className="surface-heading">
            <div>
              <p>SECKILL</p>
              <h2>秒杀券池</h2>
            </div>
            <Ticket size={23} />
          </div>

          <div className="voucher-list">
            {vouchers.map((voucher) => {
              const live = isVoucherLive(voucher);
              const disabled = isBusy || !sessionToken || !live;
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
                      <span className={live ? "live" : "quiet"}>{live ? "可抢" : "不可抢"}</span>
                      <span>{formatRange(voucher.beginTime, voucher.endTime)}</span>
                    </div>
                  </div>
                  <button
                    className="buy-button"
                    type="button"
                    disabled={disabled}
                    onClick={() => handleSeckill(voucher)}
                  >
                    {loading.action === `seckill-${voucher.id}` ? <Loader2 size={17} /> : <Zap size={17} />}
                    {loading.action === `seckill-${voucher.id}` ? "提交中" : "抢券"}
                  </button>
                </article>
              );
            })}
            {!loading.vouchers && vouchers.length === 0 && (
              <div className="empty-state tall">
                <Ticket size={24} />
                <span>当前店铺暂无可展示券</span>
              </div>
            )}
            {loading.vouchers && vouchers.length === 0 && (
              <div className="empty-state tall">
                <Loader2 size={24} />
                <span>正在加载券池</span>
              </div>
            )}
          </div>
        </section>

        <aside className="surface order-surface">
          <div className="surface-heading">
            <div>
              <p>ORDER</p>
              <h2>订单控制</h2>
            </div>
            <ReceiptText size={23} />
          </div>

          {activeOrder ? (
            <div className="order-focus">
              <div className={`status-banner ${orderMeta?.tone || "processing"}`}>
                <span className={`status-dot ${orderMeta?.tone || "processing"}`} />
                <div>
                  <p>{activeOrder.voucherTitle || "秒杀券订单"}</p>
                  <strong>{activeOrder.statusText || orderMeta?.label || "处理中"}</strong>
                  <span>{orderMeta?.copy}</span>
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
                  {loading.action === "pay" ? <Loader2 size={17} /> : <CreditCard size={17} />}
                  模拟支付
                </button>
                <button
                  className="danger-button"
                  type="button"
                  onClick={handleCancelOrder}
                  disabled={isBusy || activeOrder.status !== 1}
                >
                  {loading.action === "cancel" ? <Loader2 size={17} /> : <XCircle size={17} />}
                  取消未支付
                </button>
              </div>
            </div>
          ) : (
            <div className="empty-state tall">
              <TimerReset size={24} />
              <span>抢券后这里会跟踪异步落库状态</span>
            </div>
          )}

          <div className="mini-list-header">
            <span>最近订单</span>
            <button className="text-button" type="button" onClick={loadOrders} disabled={!sessionToken || loading.orders}>
              {loading.orders ? "刷新中" : "刷新"}
            </button>
          </div>

          <div className="order-list">
            {orders.map((order) => {
              const meta = STATUS_META[order.status] || STATUS_META[0];
              return (
                <button
                  className="order-item"
                  key={order.id}
                  type="button"
                  onClick={() => {
                    setActiveOrder(order);
                    updateNodeByStatus(order.status);
                    pushEvent("info", "切换订单", `${order.id} -> ${order.statusText}`);
                  }}
                >
                  <span className={`status-dot ${meta.tone}`} />
                  <span>{order.voucherTitle || "秒杀券"}</span>
                  <strong>{order.statusText}</strong>
                </button>
              );
            })}
            {!loading.orders && orders.length === 0 && <p className="list-empty">暂无订单</p>}
          </div>
        </aside>
      </main>

      <section className="pipeline-section">
        <div className="section-heading">
          <div>
            <p>DATA FLOW</p>
            <h2>从点击到释放的数据路径</h2>
          </div>
          <Layers3 size={22} />
        </div>

        <div className="pipeline-layout">
          <div className="pipeline-track">
            {FLOW_NODES.map((node, index) => (
              <button
                className={`flow-node ${node.id === activeNode ? "active" : ""}`}
                key={node.id}
                type="button"
                onClick={() => setActiveNode(node.id)}
              >
                <span className="node-index">{String(index + 1).padStart(2, "0")}</span>
                <strong>{node.title}</strong>
                <small>{node.label}</small>
                {index < FLOW_NODES.length - 1 && <ArrowRight className="node-arrow" size={16} />}
              </button>
            ))}
          </div>

          <div className="flow-inspector">
            <span className="inspector-kicker">{selectedNode.meta}</span>
            <strong>{selectedNode.label}</strong>
            <p>{selectedNode.detail}</p>
          </div>
        </div>
      </section>

      <section className="telemetry-grid">
        <div className="telemetry-panel">
          <div className="section-heading">
            <div>
              <p>EVENTS</p>
              <h2>请求事件流</h2>
            </div>
            <CircleDot size={21} />
          </div>
          <div className="event-list">
            {events.map((event) => (
              <div className={`event-row ${event.type}`} key={event.id}>
                <span className="event-time">{formatClock(event.time)}</span>
                <strong>{event.title}</strong>
                <span>{event.detail}</span>
              </div>
            ))}
            {events.length === 0 && <p className="list-empty">暂无事件</p>}
          </div>
        </div>

        <div className="telemetry-panel scenario-panel">
          <div className="section-heading">
            <div>
              <p>CHECKLIST</p>
              <h2>回归场景</h2>
            </div>
            <ListRestart size={21} />
          </div>
          <div className="scenario-list">
            {SCENARIOS.map((item, index) => (
              <div className="scenario-row" key={item}>
                <span>{index + 1}</span>
                <strong>{item}</strong>
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
}

function Metric({ icon, label, value, tone }) {
  return (
    <div className={`metric ${tone}`}>
      <span>{icon}</span>
      <div>
        <p>{label}</p>
        <strong>{value}</strong>
      </div>
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

function formatClock(value) {
  const date = value instanceof Date ? value : toDate(value);
  if (!date) {
    return "--";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(date);
}

function maskToken(token) {
  if (!token || token.length < 14) {
    return "token saved";
  }
  return `${token.slice(0, 8)}...${token.slice(-5)}`;
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
