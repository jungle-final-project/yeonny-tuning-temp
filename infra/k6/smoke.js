// =============================================================================
// [DEPRECATED] 이 스크립트는 더 이상 공식 smoke 테스트가 아니다.
//
// 공식 smoke는 전체 인증 믹스를 재사용하는 server-workload.js의 TEST_TYPE=smoke다:
//
//   docker run --rm \
//     -e TEST_TYPE=smoke -e BASE_URL=<대상 URL> \
//     -v "${PWD}:/work" -w /work \
//     grafana/k6:0.54.0 run infra/k6/server-workload.js
//
// 구 버전은 인증이 필요한 /api/builds/recommend, /api/parts를 비인증으로 호출해
// 401이 보장되고, 그 401이 자체 http_req_failed threshold(rate<0.01)를 항상
// 실패시키는 구조였다. 여기에는 비인증 /api/health 확인만 남긴다.
// =============================================================================
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: 1,
      duration: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const health = http.get(`${BASE_URL}/api/health`);
  check(health, { 'health 200': (res) => res.status === 200 });
  sleep(1);
}
