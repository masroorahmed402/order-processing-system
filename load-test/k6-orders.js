// 1,000 concurrent orders, each followed by an immediate client retry with the same
// Idempotency-Key. Run through scripts/run-load-test.sh, which also reconciles the results.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const SKU = __ENV.SKU || 'SKU-LOAD';

export const options = {
  scenarios: {
    orders: {
      executor: 'shared-iterations',
      vus: 1000,
      iterations: 1000,
      maxDuration: '5m',
    },
  },
  thresholds: {
    checks: ['rate==1.0'],
    http_req_failed: ['rate==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  // One unique key per iteration; the reconcile script finds this run's orders by the prefix.
  const key = `load-${RUN_ID}-${exec.scenario.iterationInTest}`;
  const body = JSON.stringify({
    customerId: `load-customer-${exec.vu.idInTest}`,
    items: [{ sku: SKU, quantity: 1, unitPrice: 9.99 }],
  });
  const headers = { 'Content-Type': 'application/json', 'Idempotency-Key': key };

  const first = http.post(`${BASE_URL}/orders`, body, { headers, timeout: '60s', tags: { call: 'create' } });
  // Simulated client retry (e.g. the client never saw the first response).
  const retry = http.post(`${BASE_URL}/orders`, body, { headers, timeout: '60s', tags: { call: 'retry' } });

  check(first, { 'first call is 201': (r) => r.status === 201 });
  check(retry, {
    'retry is 200': (r) => r.status === 200,
    'retry returns the same order id': (r) =>
      first.status === 201 && r.status === 200 && r.json('orderId') === first.json('orderId'),
  });
}
