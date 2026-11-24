import http from 'k6/http';
import { check, sleep } from 'k6';

// Baseline load test: health endpoints only. WebSocket scenarios
// (concurrent connections, message throughput, p95 delivery latency)
// land with the WebSocket slice in tests/load/websocket.js.
export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '30s', target: 50 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL ?? 'http://localhost';

export default function () {
  const res = http.get(`${BASE_URL}/api/v1/health`);
  check(res, { 'health is 200': (r) => r.status === 200 });
  sleep(1);
}
