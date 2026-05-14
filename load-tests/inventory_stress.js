import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = __ENV.PRODUCT_ID || '00000000-0000-0000-0000-000000000001';
const WAREHOUSE_ID = __ENV.WAREHOUSE_ID || '00000000-0000-0000-0000-000000000001';

// Custom metrics
const errorRate = new Rate('error_rate');
const sellLatency = new Trend('sell_latency', true);
const getLatency = new Trend('get_stock_latency', true);
const restockLatency = new Trend('restock_latency', true);

// ── Scenario selection ──
const scenario = __ENV.SCENARIO || 'steady';

export const options = scenario === 'flash_sale' ? {
  scenarios: {
    flash_sale: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 500 },
        { duration: '30s', target: 500 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    'http_req_duration{scenario:flash_sale}': ['p(99)<500'],
    'error_rate': ['rate<0.05'],
  },
} : {
  scenarios: {
    steady_state: {
      executor: 'constant-vus',
      vus: 50,
      duration: '60s',
    },
  },
  thresholds: {
    'http_req_duration': ['p(95)<200'],
    'error_rate': ['rate<0.01'],
  },
};

function getStock() {
  const res = http.get(`${BASE_URL}/api/inventory/${PRODUCT_ID}`, {
    headers: { 'X-Correlation-ID': `k6-${Date.now()}` },
  });
  getLatency.add(res.timings.duration);
  check(res, { 'GET stock 200': (r) => r.status === 200 });
  errorRate.add(res.status !== 200);
}

function sellProduct() {
  const payload = JSON.stringify({
    warehouseId: WAREHOUSE_ID,
    quantity: 1,
  });
  const res = http.post(`${BASE_URL}/api/inventory/${PRODUCT_ID}/sell`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'X-Correlation-ID': `k6-sell-${Date.now()}`,
    },
  });
  sellLatency.add(res.timings.duration);
  check(res, { 'POST sell 200': (r) => r.status === 200 });
  errorRate.add(res.status !== 200);
}

function restockProduct() {
  const payload = JSON.stringify({
    warehouseId: WAREHOUSE_ID,
    quantity: 10,
  });
  const res = http.post(`${BASE_URL}/api/inventory/${PRODUCT_ID}/restock`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'X-Correlation-ID': `k6-restock-${Date.now()}`,
    },
  });
  restockLatency.add(res.timings.duration);
  check(res, { 'POST restock 200': (r) => r.status === 200 });
  errorRate.add(res.status !== 200);
}

export default function () {
  if (scenario === 'flash_sale') {
    // 100% sell on a single hot product
    sellProduct();
  } else {
    // Steady state: 60% GET, 30% sell, 10% restock
    const rand = Math.random();
    if (rand < 0.6) {
      getStock();
    } else if (rand < 0.9) {
      sellProduct();
    } else {
      restockProduct();
    }
  }
  sleep(0.1);
}

export function handleSummary(data) {
  console.log('\n══════════════════════════════════════');
  console.log(`  Scenario: ${scenario}`);
  console.log('══════════════════════════════════════');
  console.log(`  Total Requests:  ${data.metrics.http_reqs.values.count}`);
  console.log(`  Error Rate:      ${(data.metrics.error_rate.values.rate * 100).toFixed(2)}%`);
  console.log(`  P50 Latency:     ${data.metrics.http_req_duration.values['p(50)'].toFixed(1)}ms`);
  console.log(`  P95 Latency:     ${data.metrics.http_req_duration.values['p(95)'].toFixed(1)}ms`);
  console.log(`  P99 Latency:     ${data.metrics.http_req_duration.values['p(99)'].toFixed(1)}ms`);
  console.log('══════════════════════════════════════\n');
  return {};
}
