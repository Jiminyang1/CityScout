import { ArrowLeft, MessageSquareText } from "lucide-react";
import { FormEvent, useMemo, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { sendLoginCode, loginWithCode } from "../api/cityScout";
import { useAuth } from "../state/auth";

interface LocationState {
  from?: string;
}

export function LoginPage() {
  const [phone, setPhone] = useState("13800000000");
  const [code, setCode] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [isSending, setIsSending] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { login } = useAuth();

  const from = useMemo(() => {
    const state = location.state as LocationState | null;
    return state?.from || "/shops";
  }, [location.state]);

  async function handleSendCode() {
    setError("");
    setMessage("");
    setIsSending(true);
    try {
      await sendLoginCode(phone);
      setMessage("验证码已发送，请查看后端日志输出。");
    } catch (err) {
      setError(err instanceof Error ? err.message : "发送验证码失败");
    } finally {
      setIsSending(false);
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setIsSubmitting(true);
    try {
      const token = await loginWithCode(phone, code);
      await login(token);
      navigate(from, { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : "登录失败");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <main className="login-screen">
      <section className="login-panel">
        <Link to="/" className="back-link">
          <ArrowLeft size={18} />
          返回 CityScout
        </Link>

        <div className="login-heading">
          <span className="brand-mark large">
            <MessageSquareText size={30} />
          </span>
          <div>
            <h1>手机号登录</h1>
            <p>登录后可以参与秒杀、查看订单并处理支付状态。</p>
          </div>
        </div>

        <form className="login-form" onSubmit={handleSubmit}>
          <label>
            手机号
            <input
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
              placeholder="请输入手机号"
              autoComplete="tel"
            />
          </label>
          <label>
            验证码
            <div className="code-row">
              <input
                value={code}
                onChange={(event) => setCode(event.target.value)}
                placeholder="6 位验证码"
                autoComplete="one-time-code"
              />
              <button type="button" className="secondary-button" onClick={handleSendCode} disabled={isSending}>
                {isSending ? "发送中" : "发送验证码"}
              </button>
            </div>
          </label>

          {message && <div className="form-message success">{message}</div>}
          {error && <div className="form-message error">{error}</div>}

          <button className="primary-button full" disabled={isSubmitting}>
            {isSubmitting ? "登录中" : "登录"}
          </button>
        </form>
      </section>
    </main>
  );
}
