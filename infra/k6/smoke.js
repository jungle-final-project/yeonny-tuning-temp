import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: 5,
      duration: '30s'
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500']
  }
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

export default function () {
  const health = http.get(`${BASE_URL}/api/health`);
  check(health, { 'health 200': (res) => res.status === 200 });

  const recommend = http.post(`${BASE_URL}/api/builds/recommend`, JSON.stringify({ requirementId: 'req-1001' }), {
    headers: { 'Content-Type': 'application/json' }
  });
  check(recommend, { 'recommend 200': (res) => res.status === 200 });

  const parts = http.get(`${BASE_URL}/api/parts`);
  check(parts, { 'parts 200': (res) => res.status === 200 });

  sleep(1);
}
