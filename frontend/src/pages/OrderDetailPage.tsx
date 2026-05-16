import { ArrowLeft, Loader2, RefreshCw } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { cancelOrder, getOrder, simulatePay } from "../api/cityScout";
import type { VoucherOrderStatus } from "../api/types";
import { StatusBadge } from "../components/StatusBadge";
import { formatDateTime } from "../lib/format";

const POLL_DELAYS_MS = [1000, 2000, 3000, 5000, 8000, 10000];

export function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const [order, setOrder] = useState<VoucherOrderStatus | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isWorking, setIsWorking] = useState(false);
  const [error, setError] = useState("");
  const [pollAttempt, setPollAttempt] = useState(0);

  const loadOrder = useCallback(async () => {
    if (!orderId) {
      return;
    }
    const nextOrder = await getOrder(orderId);
    setOrder(nextOrder);
  }, [orderId]);

  useEffect(() => {
    setIsLoading(true);
    setError("");
    setPollAttempt(0);
    loadOrder()
      .catch((err) => setError(err instanceof Error ? err.message : "订单加载失败"))
      .finally(() => setIsLoading(false));
  }, [loadOrder, orderId]);

  useEffect(() => {
    if (!order || order.status !== 0) {
      return;
    }
    const delay = POLL_DELAYS_MS[Math.min(pollAttempt, POLL_DELAYS_MS.length - 1)];
    const timer = window.setTimeout(() => {
      loadOrder()
        .catch((err) => setError(err instanceof Error ? err.message : "订单刷新失败"))
        .finally(() => setPollAttempt((current) => current + 1));
    }, delay);
    return () => window.clearTimeout(timer);
  }, [loadOrder, order, pollAttempt]);

  async function runAction(action: (id: string) => Promise<VoucherOrderStatus>) {
    if (!orderId) {
      return;
    }
    setIsWorking(true);
    setError("");
    try {
      setOrder(await action(orderId));
    } catch (err) {
      setError(err instanceof Error ? err.message : "订单操作失败");
    } finally {
      setIsWorking(false);
    }
  }

  if (isLoading) {
    return (
      <div className="loading-state page-center">
        <Loader2 size={22} className="spin" />
        正在加载订单
      </div>
    );
  }

  if (error && !order) {
    return <div className="empty-state error page-center">{error}</div>;
  }

  if (!order) {
    return <div className="empty-state page-center">订单不存在</div>;
  }

  return (
    <section className="order-detail">
      <Link to="/orders" className="back-link inline">
        <ArrowLeft size={18} />
        返回订单
      </Link>

      <div className="order-detail-card">
        <div className="order-title-row">
          <div>
            <p className="eyebrow">Order Detail</p>
            <h1>{order.voucherTitle || `优惠券 #${order.voucherId}`}</h1>
          </div>
          <StatusBadge status={order.status} text={order.statusText} />
        </div>

        {order.status === 0 && (
          <div className="processing-line">
            <RefreshCw size={17} className="spin" />
            正在等待 Kafka 异步创建订单
          </div>
        )}
        {error && <div className="form-message error">{error}</div>}

        <dl className="order-facts">
          <div>
            <dt>订单号</dt>
            <dd>{order.id}</dd>
          </div>
          <div>
            <dt>券 ID</dt>
            <dd>{order.voucherId ?? "-"}</dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{formatDateTime(order.createTime)}</dd>
          </div>
          <div>
            <dt>支付时间</dt>
            <dd>{formatDateTime(order.payTime)}</dd>
          </div>
          <div>
            <dt>更新时间</dt>
            <dd>{formatDateTime(order.updateTime)}</dd>
          </div>
        </dl>

        {order.status === 1 && (
          <div className="detail-actions">
            <button
              className="primary-button"
              onClick={() => runAction(simulatePay)}
              disabled={isWorking}
            >
              模拟支付
            </button>
            <button
              className="danger-button"
              onClick={() => runAction(cancelOrder)}
              disabled={isWorking}
            >
              取消订单
            </button>
          </div>
        )}
      </div>
    </section>
  );
}
