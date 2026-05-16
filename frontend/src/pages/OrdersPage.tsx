import { Loader2, ReceiptText } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { cancelOrder, getMyOrders, simulatePay } from "../api/cityScout";
import type { VoucherOrderStatus } from "../api/types";
import { StatusBadge } from "../components/StatusBadge";
import { formatDateTime } from "../lib/format";

export function OrdersPage() {
  const [orders, setOrders] = useState<VoucherOrderStatus[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [workingId, setWorkingId] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    getMyOrders()
      .then(setOrders)
      .catch((err) => setError(err instanceof Error ? err.message : "订单加载失败"))
      .finally(() => setIsLoading(false));
  }, []);

  async function runAction(
    orderId: string,
    action: (id: string) => Promise<VoucherOrderStatus>
  ) {
    setWorkingId(orderId);
    setError("");
    try {
      const nextOrder = await action(orderId);
      setOrders((current) =>
        current.map((order) => (order.id === orderId ? nextOrder : order))
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "订单操作失败");
    } finally {
      setWorkingId("");
    }
  }

  return (
    <section className="orders-layout">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Orders</p>
          <h1>我的订单</h1>
        </div>
        <Link to="/shops" className="secondary-button">
          继续逛店
        </Link>
      </div>

      {error && <div className="form-message error">{error}</div>}
      {isLoading && (
        <div className="loading-state">
          <Loader2 size={20} className="spin" />
          正在加载订单
        </div>
      )}

      {!isLoading && orders.length === 0 && (
        <div className="empty-state">
          <ReceiptText size={32} />
          暂无订单
        </div>
      )}

      <div className="order-list">
        {orders.map((order) => (
          <article className="order-row" key={order.id}>
            <div>
              <StatusBadge status={order.status} text={order.statusText} />
              <h2>{order.voucherTitle || `优惠券 #${order.voucherId}`}</h2>
              <p>订单号 {order.id}</p>
              <p>创建时间 {formatDateTime(order.createTime)}</p>
            </div>
            <div className="order-actions">
              <Link className="secondary-button" to={`/orders/${order.id}`}>
                详情
              </Link>
              {order.status === 1 && (
                <>
                  <button
                    className="primary-button"
                    onClick={() => runAction(order.id, simulatePay)}
                    disabled={workingId === order.id}
                  >
                    模拟支付
                  </button>
                  <button
                    className="danger-button"
                    onClick={() => runAction(order.id, cancelOrder)}
                    disabled={workingId === order.id}
                  >
                    取消
                  </button>
                </>
              )}
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}
