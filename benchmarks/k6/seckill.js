import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const configPath = __ENV.CONFIG_FILE || 'benchmarks/k6/config.json';
const tokensPath = __ENV.TOKENS_FILE || 'benchmarks/data/tokens.json';

const cfg = JSON.parse(open(configPath));
const tokens = JSON.parse(open(tokensPath));

if (!Array.isArray(tokens) || tokens.length === 0) {
  throw new Error('tokens.json is empty. run generate_token_pool.sh first');
}

const warmupSeconds = cfg.warmupSeconds || 10;
const durationSeconds = cfg.durationSeconds || 30;
const totalSeconds = warmupSeconds + durationSeconds;
const timeoutMs = cfg.timeoutMs || 2000;

const benchRequestCount = new Counter('bench_request_count');
const benchSuccessCount = new Counter('bench_success_count');
const benchNoStockCount = new Counter('bench_no_stock_count');
const benchDuplicateCount = new Counter('bench_duplicate_count');
const benchLockBusyCount = new Counter('bench_lock_busy_count');
const benchMqErrorCount = new Counter('bench_mq_error_count');
const benchOtherCodeCount = new Counter('bench_other_code_count');
const benchNon200Count = new Counter('bench_non_200_count');
const benchHttp5xxCount = new Counter('bench_http_5xx_count');
const benchLatency = new Trend('bench_latency', true);
const benchErrorRate = new Rate('bench_error_rate');

const startTs = Date.now();

export const options = {
  scenarios: {
    seckill: {
      executor: 'constant-arrival-rate',
      rate: cfg.rate,
      timeUnit: '1s',
      duration: `${totalSeconds}s`,
      preAllocatedVUs: cfg.preAllocatedVUs || 400,
      maxVUs: cfg.maxVUs || 20000
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.2'],
    bench_error_rate: ['rate<0.8']
  }
};

function markCode(code) {
  switch (code) {
    case 'SUCCESS':
      benchSuccessCount.add(1);
      break;
    case 'NO_STOCK':
      benchNoStockCount.add(1);
      break;
    case 'DUPLICATE_ORDER':
      benchDuplicateCount.add(1);
      break;
    case 'LOCK_BUSY':
      benchLockBusyCount.add(1);
      break;
    case 'MQ_ERROR':
      benchMqErrorCount.add(1);
      break;
    default:
      benchOtherCodeCount.add(1);
      break;
  }
}

export default function () {
  const elapsed = (Date.now() - startTs) / 1000;
  const inMeasureWindow = elapsed >= warmupSeconds;

  const idx = exec.scenario.iterationInTest % tokens.length;
  const token = tokens[idx];
  const url = `${cfg.baseUrl}/api/benchmark/voucher-order/seckill/${cfg.variant}/${cfg.voucherId}`;

  const res = http.post(url, null, {
    headers: {
      authorization: token
    },
    timeout: `${timeoutMs}ms`
  });

  if (!inMeasureWindow) {
    return;
  }

  benchRequestCount.add(1);
  benchLatency.add(res.timings.duration);

  if (res.status !== 200) {
    benchNon200Count.add(1);
    if (res.status >= 500) {
      benchHttp5xxCount.add(1);
    }
    benchErrorRate.add(1);
    return;
  }

  let body = null;
  try {
    body = res.json();
  } catch (e) {
    benchOtherCodeCount.add(1);
    benchErrorRate.add(1);
    return;
  }

  const code = body && body.code ? body.code : 'UNKNOWN';
  markCode(code);

  if (code === 'SUCCESS' || code === 'NO_STOCK' || code === 'DUPLICATE_ORDER') {
    benchErrorRate.add(0);
  } else {
    benchErrorRate.add(1);
  }
}
