import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Rate, Trend } from 'k6/metrics';

// 6종 테스트를 TEST_TYPE 환경변수 하나로 전환한다:
//   smoke | load | stress | spike | soak | breakpoint (capacity는 breakpoint의 구명 별칭)
// 모든 프로필이 arrival-rate 실행기를 사용하므로 결과를 VU 수가 아니라 RPS(도착률)로 설명한다.
// pacing은 실행기 책임이므로 think time은 arrival-rate에서 0으로 강제된다(coordinated omission 방지).
const TEST_TYPE = (__ENV.TEST_TYPE || 'load').toLowerCase();
const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:18082';
const USER_EMAIL = __ENV.USER_EMAIL || 'user@example.com';
const USER_PASSWORD = __ENV.USER_PASSWORD || 'passw0rd!';
const SUMMARY_PATH = __ENV.SUMMARY_PATH || `/work/infra/k6/reports/server-${TEST_TYPE}-summary.json`;
const SOAK_WINDOW_MINUTES = Number(__ENV.SOAK_WINDOW_MINUTES || '5');
const SOAK_WINDOW_COUNT = Number(__ENV.SOAK_WINDOW_COUNT || '12');
const SOAK_RATE = Number(__ENV.SOAK_RATE || '30');
const SOAK_DURATION = __ENV.SOAK_DURATION || '5m';
// 반복 로그인 비중. AWS 실측에서는 0.005 또는 0을 권장한다 — 로그인마다 refresh token row가
// 쌓이고(60분 soak 기준 수만 row) BCrypt cost-10이 CPU를 소모한다. LOGIN_RATIO=0이어도
// 보호 API의 401→refresh 경로가 auth/auth_refresh를 자연스럽게 발생시킨다.
const LOGIN_RATIO = Number(__ENV.LOGIN_RATIO || '0.05');
// SLO 프로필: local(기존 로컬 기준값 유지) | aws(docs/AWS_LOAD_TEST_PREFLIGHT.md §6 합격 기준)
const SLO_PROFILE = (__ENV.SLO_PROFILE || 'local').toLowerCase();
// VU 최초 로그인 지터: spike 시작 시 수백 VU가 동시에 bcrypt 로그인해 CPU 벽을 만드는 것을 분산한다.
const STARTUP_JITTER_SECONDS = Number(__ENV.STARTUP_JITTER_SECONDS || '2');
// SHORT=1이면 load를 60분 램프 대신 "20 rps 램프 후 10분 유지" 축약판으로 실행한다.
const SHORT = ['1', 'true', 'yes'].indexOf(String(__ENV.SHORT || '').toLowerCase()) >= 0;

// breakpoint 튜닝: 단계 도착률 목록과 램프/유지 시간.
// BREAKPOINT_MAX_RATE를 주면 그 이하 단계만 남긴다(예: 200이면 50,100,200).
const BREAKPOINT_MAX_RATE = Number(__ENV.BREAKPOINT_MAX_RATE || '0');
const BREAKPOINT_LEVELS = (__ENV.BREAKPOINT_LEVELS || '50,100,200,300,400')
  .split(',')
  .map((value) => Number(value.trim()))
  .filter((value) => value > 0 && (BREAKPOINT_MAX_RATE <= 0 || value <= BREAKPOINT_MAX_RATE));
const BREAKPOINT_RAMP = __ENV.BREAKPOINT_RAMP || '2m';
const BREAKPOINT_HOLD = __ENV.BREAKPOINT_HOLD || '10m';

const contractErrors = new Rate('contract_errors');
const soakWindowDurations = [];
const soakWindowFailures = [];
let vuAccessToken = null;
let vuRefreshToken = null;
let vuStartupJitterDone = false;
for (let index = 0; index < SOAK_WINDOW_COUNT; index += 1) {
  const suffix = String(index + 1).padStart(2, '0');
  soakWindowDurations.push(new Trend(`soak_window_${suffix}_duration`, true));
  soakWindowFailures.push(new Rate(`soak_window_${suffix}_failures`));
}

// 단계별 "짧은 램프 + 유지" 사다리 스테이지를 만든다. 의도적으로 ramp-down 단계가 없다 —
// breakpoint는 knee 이후를 abortOnFail threshold가 중단시키는 구조다.
function holdLadderStages(levels, ramp, hold) {
  const stages = [];
  for (const level of levels) {
    stages.push({ duration: ramp, target: level });
    stages.push({ duration: hold, target: level });
  }
  return stages;
}

// docs/AWS_LOAD_TEST_PREFLIGHT.md §5의 1시간 램프. 각 10분 구간은 30초 램프 + 9분30초 유지.
const LOAD_STAGES_FULL = [
  { duration: '5m', target: 2 }, // 0~5분: 2 rps — 인증·계약·관측 수집 확인
  { duration: '30s', target: 5 }, { duration: '9m30s', target: 5 }, // ~15분: 5 rps 기준선
  { duration: '30s', target: 10 }, { duration: '9m30s', target: 10 }, // ~25분: 10 rps
  { duration: '30s', target: 20 }, { duration: '9m30s', target: 20 }, // ~35분: 20 rps
  { duration: '30s', target: 40 }, { duration: '9m30s', target: 40 }, // ~45분: 40 rps
  { duration: '30s', target: 80 }, { duration: '9m30s', target: 80 }, // ~55분: 80 rps 첫 시험 상한
  { duration: '30s', target: 5 }, { duration: '4m30s', target: 5 }, // ~60분: 부하 제거 후 회복 확인
];
// SHORT=1 축약판: 20 rps까지 램프 후 10분 유지.
const LOAD_STAGES_SHORT = [
  { duration: '2m', target: 20 },
  { duration: '10m', target: 20 },
];

const profiles = {
  // 배선 확인용 1분 스모크: 전체 인증 믹스를 그대로 재사용한다(구 infra/k6/smoke.js 대체).
  smoke: {
    executor: 'constant-arrival-rate',
    rate: 2,
    timeUnit: '1s',
    duration: '60s',
    preAllocatedVUs: 5,
    maxVUs: 10,
    gracefulStop: '10s',
  },
  load: {
    executor: 'ramping-arrival-rate',
    startRate: 2,
    timeUnit: '1s',
    preAllocatedVUs: 100,
    maxVUs: 400,
    stages: SHORT ? LOAD_STAGES_SHORT : LOAD_STAGES_FULL,
    gracefulStop: '10s',
  },
  stress: {
    executor: 'ramping-arrival-rate',
    startRate: 0,
    timeUnit: '1s',
    preAllocatedVUs: 100,
    maxVUs: 400,
    stages: [
      { duration: '1m', target: 20 }, { duration: '3m', target: 20 },
      { duration: '1m', target: 40 }, { duration: '3m', target: 40 },
      { duration: '1m', target: 60 }, { duration: '3m', target: 60 },
      { duration: '1m', target: 80 }, { duration: '3m', target: 80 },
      { duration: '1m', target: 100 }, { duration: '3m', target: 100 },
    ],
    gracefulStop: '10s',
  },
  spike: {
    executor: 'ramping-arrival-rate',
    startRate: 5,
    timeUnit: '1s',
    preAllocatedVUs: 100,
    maxVUs: 400,
    stages: [
      { duration: '2m', target: 5 }, // 기준선 5 rps
      { duration: '10s', target: 100 }, // 급증
      { duration: '1m50s', target: 100 }, // 100 rps 유지(총 2분)
      { duration: '10s', target: 5 }, // 급감
      { duration: '2m50s', target: 5 }, // 회복 관찰(총 3분)
    ],
    gracefulStop: '10s',
  },
  soak: {
    executor: 'constant-arrival-rate',
    rate: SOAK_RATE,
    timeUnit: '1s',
    duration: SOAK_DURATION,
    preAllocatedVUs: 100,
    maxVUs: 400,
    gracefulStop: '10s',
  },
  // 구 capacity를 진짜 breakpoint 테스트로 재작업: 단계별 2분 램프 + 10분 유지,
  // ramp-down 없음, 포화(knee)에서 abortOnFail threshold가 실행을 중단한다.
  breakpoint: {
    executor: 'ramping-arrival-rate',
    startRate: 0,
    timeUnit: '1s',
    preAllocatedVUs: 200,
    maxVUs: 1000,
    stages: holdLadderStages(BREAKPOINT_LEVELS, BREAKPOINT_RAMP, BREAKPOINT_HOLD),
    gracefulStop: '10s',
  },
};
// 구명 호환: capacity는 breakpoint와 같은 프로필이다.
profiles.capacity = profiles.breakpoint;

if (!profiles[TEST_TYPE]) {
  throw new Error(`Unsupported TEST_TYPE=${TEST_TYPE} (smoke|load|stress|spike|soak|breakpoint|capacity)`);
}

const ACTIVE_PROFILE = profiles[TEST_TYPE];
const IS_BREAKPOINT = TEST_TYPE === 'breakpoint' || TEST_TYPE === 'capacity';
const IS_ARRIVAL_RATE = String(ACTIVE_PROFILE.executor).indexOf('arrival-rate') >= 0;
// arrival-rate에서 iteration 내부 sleep은 자기-교축(self-throttling)만 만들므로 0으로 강제한다.
const THINK_TIME_SECONDS = IS_ARRIVAL_RATE ? 0 : Number(__ENV.THINK_TIME_SECONDS || '0.2');

// 트래픽 믹스(docs/AWS_LOAD_TEST_PREFLIGHT.md §5 문서 비중):
//   login LOGIN_RATIO / health 10% / parts 30% / 홈 추천 5% / 견적초안 15%
//   / 견적 이력 10% / 가격 알림 5% / 조립 요청 10% / AI fast 10%
// LOGIN_RATIO가 0.05가 아니면 나머지 비중을 (1-LOGIN_RATIO)에 비례 배분해 합을 1로 유지한다.
const MIX_BASE = [
  ['health', 0.10],
  ['parts', 0.30],
  ['home_recommendations', 0.05],
  ['quote_draft', 0.15],
  ['build_history', 0.10],
  ['price_alerts', 0.05],
  ['assembly_requests', 0.10],
  ['ai_fast', 0.10],
];
const MIX_BASE_TOTAL = MIX_BASE.reduce((sum, entry) => sum + entry[1], 0);
const EFFECTIVE_MIX = [['auth_login', LOGIN_RATIO]];
for (const [name, weight] of MIX_BASE) {
  EFFECTIVE_MIX.push([name, weight * ((1 - LOGIN_RATIO) / MIX_BASE_TOTAL)]);
}
const MIX_BOUNDARIES = [];
{
  let cumulative = 0;
  for (const [name, weight] of EFFECTIVE_MIX) {
    cumulative += weight;
    MIX_BOUNDARIES.push([name, cumulative]);
  }
}

// 기존 로컬 기준값(SLO_PROFILE=local 기본).
const LOCAL_ENDPOINT_THRESHOLDS = {
  'http_req_duration{endpoint:health}': ['p(95)<300'],
  'http_req_duration{endpoint:parts}': ['p(95)<800'],
  'http_req_duration{endpoint:auth}': ['p(95)<1200'],
  'http_req_duration{endpoint:auth_refresh}': ['p(95)<1200'],
  'http_req_duration{endpoint:ai_fast}': ['p(95)<800'],
  'http_req_duration{endpoint:home_recommendations}': ['p(95)<1500'],
  'http_req_duration{endpoint:quote_draft}': ['p(95)<1000'],
  'http_req_duration{endpoint:build_history}': ['p(95)<1000'],
  'http_req_duration{endpoint:price_alerts}': ['p(95)<1000'],
  'http_req_duration{endpoint:assembly_requests}': ['p(95)<1000'],
};
// docs/AWS_LOAD_TEST_PREFLIGHT.md §6 잠정 합격 기준(SLO_PROFILE=aws).
const AWS_ENDPOINT_THRESHOLDS = {
  'http_req_duration{endpoint:health}': ['p(95)<100', 'p(99)<250'],
  'http_req_duration{endpoint:parts}': ['p(95)<300', 'p(99)<800'],
  'http_req_duration{endpoint:quote_draft}': ['p(95)<300', 'p(99)<800'],
  'http_req_duration{endpoint:build_history}': ['p(95)<300', 'p(99)<800'],
  'http_req_duration{endpoint:price_alerts}': ['p(95)<300', 'p(99)<800'],
  'http_req_duration{endpoint:assembly_requests}': ['p(95)<300', 'p(99)<800'],
  'http_req_duration{endpoint:auth}': ['p(95)<700', 'p(99)<1500'],
  'http_req_duration{endpoint:auth_refresh}': ['p(95)<700', 'p(99)<1500'],
  'http_req_duration{endpoint:home_recommendations}': ['p(95)<800', 'p(99)<1500'],
  'http_req_duration{endpoint:ai_fast}': ['p(95)<500', 'p(99)<1000'],
};

const thresholds = Object.assign(
  {
    checks: ['rate>0.99'],
    contract_errors: ['rate<0.01'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1500'],
    // preflight §6: dropped_iterations = 0 (injector VU 부족/SUT 지연 감지)
    dropped_iterations: ['count==0'],
  },
  SLO_PROFILE === 'aws' ? AWS_ENDPOINT_THRESHOLDS : LOCAL_ENDPOINT_THRESHOLDS,
);
if (IS_BREAKPOINT) {
  // 포화 지점(knee) 자동 중단: 오류율/지연이 기준을 2분 넘게 어기면 abort.
  thresholds.http_req_failed = [
    { threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '2m' },
  ];
  thresholds.http_req_duration = [
    { threshold: 'p(95)<2000', abortOnFail: true, delayAbortEval: '2m' },
  ];
}

export const options = {
  scenarios: { [TEST_TYPE]: ACTIVE_PROFILE },
  thresholds,
  userAgent: `BuildGraph-k6/${TEST_TYPE}`,
  noConnectionReuse: false,
};

export function setup() {
  const response = login();
  if (response.status !== 200) {
    fail(`setup login failed: HTTP ${response.status}`);
  }
  const payload = response.json();
  if (!payload || !payload.accessToken) {
    fail('setup login response did not contain accessToken');
  }
  return {};
}

const SCENARIO_FUNCS = {
  auth_login: exerciseLogin,
  health: exerciseHealth,
  parts: exerciseParts,
  home_recommendations: exerciseHomeRecommendations,
  quote_draft: exerciseDraft,
  build_history: exerciseBuildHistory,
  price_alerts: exercisePriceAlerts,
  assembly_requests: exerciseAssemblyRequests,
  ai_fast: exerciseFastAi,
};

export default function () {
  ensureSession();
  const roll = Math.random();
  let selected = MIX_BOUNDARIES[MIX_BOUNDARIES.length - 1][0];
  for (const [name, boundary] of MIX_BOUNDARIES) {
    if (roll < boundary) {
      selected = name;
      break;
    }
  }
  SCENARIO_FUNCS[selected]();
  if (THINK_TIME_SECONDS > 0) {
    sleep(THINK_TIME_SECONDS * (0.75 + Math.random() * 0.5));
  }
}

function login() {
  return http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({
    email: USER_EMAIL,
    password: USER_PASSWORD,
  }), jsonParams('auth'));
}

function exerciseLogin() {
  const response = login();
  verify(response, 'auth', (body) => Boolean(body.accessToken));
}

function exerciseHealth() {
  const response = http.get(`${BASE_URL}/api/health`, taggedParams('health'));
  verify(response, 'health', (body) => body.status === 'UP' && body.database === 'UP');
}

function exerciseParts() {
  const category = ['CPU', 'GPU', 'RAM', 'STORAGE', 'PSU', 'CASE'][Math.floor(Math.random() * 6)];
  const response = authorizedGet(
    `${BASE_URL}/api/parts?category=${category}&page=0&size=20`,
    'parts',
  );
  verify(response, 'parts', (body) => Array.isArray(body.items));
}

function exerciseHomeRecommendations() {
  const response = authorizedGet(
    `${BASE_URL}/api/recommendations/home-parts?limit=8`,
    'home_recommendations',
  );
  verify(response, 'home_recommendations', (body) => Array.isArray(body.items));
}

function exerciseDraft() {
  const response = authorizedGet(`${BASE_URL}/api/quote-drafts/current`, 'quote_draft');
  verify(response, 'quote_draft', (body) => typeof body.status === 'string');
}

function exerciseBuildHistory() {
  const response = authorizedGet(`${BASE_URL}/api/builds/history?page=0&size=20`, 'build_history');
  verify(response, 'build_history', (body) => Array.isArray(body.items));
}

function exercisePriceAlerts() {
  const response = authorizedGet(`${BASE_URL}/api/price-alerts?page=0&size=20`, 'price_alerts');
  verify(response, 'price_alerts', (body) => Array.isArray(body.items));
}

function exerciseAssemblyRequests() {
  const response = authorizedGet(`${BASE_URL}/api/assembly-requests`, 'assembly_requests');
  verify(response, 'assembly_requests', (body) => Array.isArray(body.items));
}

function exerciseFastAi() {
  const response = authorizedPost(`${BASE_URL}/api/ai/build-chat`, JSON.stringify({
    message: '램 위치가 어디 있어?',
    uiContext: { surface: 'SELF_QUOTE', capabilities: ['BOARD_PART_FOCUS'] },
  }), 'ai_fast');
  verify(response, 'ai_fast', (body) => (
    body.boardFocus
    && body.boardFocus.type === 'PART_LOCATION'
    && Array.isArray(body.boardFocus.categories)
    && body.boardFocus.categories.includes('RAM')
  ));
}

function ensureSession() {
  if (vuAccessToken) {
    return true;
  }
  // VU 최초 로그인 지터: spike/breakpoint 시작 시 모든 VU가 동시에 BCrypt 로그인해
  // CPU를 독점하는 것을 분산한다. VU당 한 번만 적용된다.
  if (!vuStartupJitterDone) {
    vuStartupJitterDone = true;
    if (STARTUP_JITTER_SECONDS > 0) {
      sleep(Math.random() * STARTUP_JITTER_SECONDS);
    }
  }
  return authenticateSession();
}

function authenticateSession() {
  return updateSession(login());
}

function refreshSession() {
  if (!vuRefreshToken) {
    return authenticateSession();
  }
  const response = http.post(`${BASE_URL}/api/auth/refresh`, JSON.stringify({
    refreshToken: vuRefreshToken,
  }), jsonParams('auth_refresh'));
  if (updateSession(response)) {
    return true;
  }
  vuAccessToken = null;
  vuRefreshToken = null;
  return authenticateSession();
}

function updateSession(response) {
  if (!response || response.status !== 200) {
    return false;
  }
  let payload = null;
  try {
    payload = response.json();
  } catch (_) {
    return false;
  }
  if (!payload || !payload.accessToken) {
    return false;
  }
  vuAccessToken = payload.accessToken;
  if (payload.refreshToken) {
    vuRefreshToken = payload.refreshToken;
  }
  return true;
}

function authorizedGet(url, endpoint) {
  let response = http.get(url, authParams(vuAccessToken, endpoint));
  if (response.status === 401 && refreshSession()) {
    response = http.get(url, authParams(vuAccessToken, endpoint));
  }
  return response;
}

function authorizedPost(url, body, endpoint) {
  let response = http.post(url, body, authJsonParams(vuAccessToken, endpoint));
  if (response.status === 401 && refreshSession()) {
    response = http.post(url, body, authJsonParams(vuAccessToken, endpoint));
  }
  return response;
}

function verify(response, endpoint, contract) {
  let body = null;
  try {
    body = response.json();
  } catch (_) {
    body = null;
  }
  const ok = check(response, {
    [`${endpoint}: HTTP 200`]: (res) => res.status === 200,
    [`${endpoint}: response contract`]: () => Boolean(body && contract(body)),
  });
  contractErrors.add(!ok, { endpoint });
  recordSoakWindow(response, ok);
}

function recordSoakWindow(response, ok) {
  if (TEST_TYPE !== 'soak' || SOAK_WINDOW_MINUTES <= 0 || SOAK_WINDOW_COUNT <= 0) {
    return;
  }
  const elapsedMs = exec.instance.currentTestRunDuration;
  const windowMs = SOAK_WINDOW_MINUTES * 60 * 1000;
  const index = Math.min(Math.floor(elapsedMs / windowMs), SOAK_WINDOW_COUNT - 1);
  soakWindowDurations[index].add(response.timings.duration);
  soakWindowFailures[index].add(!ok || response.status !== 200);
}

function taggedParams(endpoint) {
  return { tags: { endpoint }, timeout: '10s' };
}

function jsonParams(endpoint) {
  return {
    ...taggedParams(endpoint),
    headers: { 'Content-Type': 'application/json' },
  };
}

function authParams(token, endpoint) {
  return {
    ...taggedParams(endpoint),
    headers: { Authorization: `Bearer ${token}` },
  };
}

function authJsonParams(token, endpoint) {
  return {
    ...taggedParams(endpoint),
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'X-BuildGraph-AI-Profile': 'BUILD_CHAT_54_MINI_FAST',
    },
  };
}

export function handleSummary(data) {
  const duration = data.metrics.http_req_duration && data.metrics.http_req_duration.values;
  const failed = data.metrics.http_req_failed && data.metrics.http_req_failed.values;
  const requests = data.metrics.http_reqs && data.metrics.http_reqs.values;
  const checks = data.metrics.checks && data.metrics.checks.values;
  const dropped = data.metrics.dropped_iterations && data.metrics.dropped_iterations.values;
  const mixParts = [];
  const effectiveMixObject = {};
  for (const [name, weight] of EFFECTIVE_MIX) {
    mixParts.push(`${name}:${weight.toFixed(3)}`);
    effectiveMixObject[name] = Number(weight.toFixed(4));
  }
  const text = [
    `BuildGraph server ${TEST_TYPE} test`,
    `requests=${requests ? requests.count : 0}`,
    `requestRate=${requests ? requests.rate.toFixed(2) : 0}/s`,
    `avg=${duration ? duration.avg.toFixed(2) : 0}ms`,
    `p95=${duration ? duration['p(95)'].toFixed(2) : 0}ms`,
    `max=${duration ? duration.max.toFixed(2) : 0}ms`,
    `failedRate=${failed ? failed.rate.toFixed(4) : 0}`,
    `checkRate=${checks ? checks.rate.toFixed(4) : 0}`,
    `droppedIterations=${dropped ? dropped.count : 0}`,
    `sloProfile=${SLO_PROFILE}`,
    `loginRatio=${LOGIN_RATIO}`,
    `offeredMix=${mixParts.join(' ')}`,
    '',
  ].join('\n');
  // 실행 manifest에 offered mix가 남도록 summary JSON에 실행 구성을 함께 기록한다.
  const summary = Object.assign({}, data, {
    buildgraph: {
      testType: TEST_TYPE,
      executor: ACTIVE_PROFILE.executor,
      sloProfile: SLO_PROFILE,
      loginRatio: LOGIN_RATIO,
      thinkTimeSeconds: THINK_TIME_SECONDS,
      startupJitterSeconds: STARTUP_JITTER_SECONDS,
      effectiveMix: effectiveMixObject,
    },
  });
  return {
    stdout: text,
    [SUMMARY_PATH]: JSON.stringify(summary, null, 2),
  };
}
