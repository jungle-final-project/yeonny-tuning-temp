import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { authHeaders, baseUrl, loginForVu, requestTimeout } from '../util/general-helper.js';

const accessTokens = {};

const PART_CATEGORIES = ['CPU', 'GPU', 'MOTHERBOARD', 'RAM', 'STORAGE', 'PSU', 'CASE', 'COOLER'];
const HOME_PART_LIMIT = Number(__ENV.HOME_PART_LIMIT || '5');
const HOME_START_RATE = Number(__ENV.HOME_START_RATE || '2');
const HOME_PEAK_RATE = Number(__ENV.HOME_PEAK_RATE || '8');
const HOME_PRE_ALLOCATED_VUS = Number(__ENV.HOME_PRE_ALLOCATED_VUS || '50');
const HOME_MAX_VUS = Number(__ENV.HOME_MAX_VUS || '200');
const HOME_RUN_PUBLIC = boolEnv('HOME_RUN_PUBLIC', true);
const HOME_RUN_AUTH = boolEnv('HOME_RUN_AUTH', true);
const HOME_RECORD_EVENTS = boolEnv('HOME_RECORD_EVENTS', true);
const HOME_EVENT_DELAY_MS = Number(__ENV.HOME_EVENT_DELAY_MS || '0');
const HOME_EVENT_JITTER_MS = Number(__ENV.HOME_EVENT_JITTER_MS || '0');

const scenarios = {};

if (HOME_RUN_PUBLIC) {
  scenarios.public_home_first_visit = {
    executor: 'ramping-arrival-rate',
    exec: 'publicHomeFirstVisit',
    startRate: HOME_START_RATE,
    timeUnit: '1s',
    preAllocatedVUs: HOME_PRE_ALLOCATED_VUS,
    maxVUs: HOME_MAX_VUS,
    stages: homeStages(),
    startTime: '0s',
    env: {
      BASE_URL: __ENV.HOME_PUBLIC_BASE_URL || __ENV.BASE_URL || 'http://localhost:8080',
      K6_FLOW: 'home',
      K6_VARIANT: __ENV.K6_VARIANT || 'public'
    },
    tags: {
      flow: 'home',
      visitor: 'public'
    }
  };
}

if (HOME_RUN_AUTH) {
  scenarios.authenticated_home_first_visit = {
    executor: 'ramping-arrival-rate',
    exec: 'authenticatedHomeFirstVisit',
    startRate: HOME_START_RATE,
    timeUnit: '1s',
    preAllocatedVUs: HOME_PRE_ALLOCATED_VUS,
    maxVUs: HOME_MAX_VUS,
    stages: homeStages(),
    startTime: __ENV.HOME_AUTH_START_TIME || '45s',
    env: {
      BASE_URL: __ENV.HOME_AUTH_BASE_URL || __ENV.BASE_URL || 'http://localhost:8080',
      K6_FLOW: 'home',
      K6_VARIANT: __ENV.K6_VARIANT || 'authenticated'
    },
    tags: {
      flow: 'home',
      visitor: 'authenticated'
    }
  };
}

if (!HOME_RUN_PUBLIC && !HOME_RUN_AUTH) {
  throw new Error('At least one of HOME_RUN_PUBLIC or HOME_RUN_AUTH must be true.');
}

export const options = { scenarios };

function homeStages() {
  return [
    { target: HOME_START_RATE, duration: __ENV.HOME_RAMP_UP_DURATION || '5s' },
    { target: HOME_PEAK_RATE, duration: __ENV.HOME_STEADY_DURATION || '20s' },
    { target: 1, duration: __ENV.HOME_RAMP_DOWN_DURATION || '5s' }
  ];
}

function tokenForVu() {
  const cacheKey = __ENV.CACHE_MODE || __ENV.K6_VARIANT || 'home';
  if (!accessTokens[cacheKey]) {
    const accountPoolSize = Number(__ENV.TEST_USER_COUNT || '500');
    const accountIndex = ((exec.vu.idInTest - 1) % accountPoolSize) + 1;
    accessTokens[cacheKey] = loginForVu(accountIndex);
  }
  return accessTokens[cacheKey];
}

export function publicHomeFirstVisit() {
  const response = getPublicHome();
  check(response, {
    'public home 200': (res) => res.status === 200,
    'public home has category parts': (res) => hasObjectField(res, 'categoryParts'),
    'public home has recommended parts': (res) => hasObjectField(res, 'recommendedParts')
  });

  sleep(0.5 + Math.random());
}

export function authenticatedHomeFirstVisit() {
  const token = tokenForVu();

  const homeResponse = getAuthenticatedHome(token);
  check(homeResponse, {
    'authenticated home 200': (res) => res.status === 200,
    'authenticated home has category parts': (res) => hasObjectField(res, 'categoryParts'),
    'authenticated home has recommended parts': (res) => hasObjectField(res, 'recommendedParts'),
    'authenticated home recommended parts has items': (res) => {
      const body = parseJson(res);
      return Array.isArray(body?.recommendedParts?.items);
    }
  });
  for (const category of PART_CATEGORIES) {
    check(homeResponse, {
      [`authenticated home ${category} category has items`]: (res) => {
        const body = parseJson(res);
        return Array.isArray(body?.categoryParts?.[category]);
      }
    });
  }

  if (HOME_RECORD_EVENTS) {
    sleepBeforeHomeEvent();
    recordHomeRecommendedPartImpressions(token, homeResponse);
  }

  sleep(0.5 + Math.random());
}

function getPublicHome() {
  return http.get(`${baseUrl()}/api/public/home`, {
    tags: homeTags('public-home', { endpoint: 'public_home', visitor: 'public' }),
    timeout: requestTimeout()
  });
}

function getAuthenticatedHome(token) {
  return http.get(`${baseUrl()}/api/home`, {
    headers: authHeaders(token),
    tags: homeTags('authenticated-home', { endpoint: 'home', visitor: 'authenticated' }),
    timeout: requestTimeout()
  });
}

function recordHomeRecommendedPartImpressions(token, response) {
  if (response.status !== 200) return;
  const body = parseJson(response);
  const sourceItems = body?.recommendedParts?.items ?? body?.items;
  const items = Array.isArray(sourceItems) ? sourceItems.slice(0, HOME_PART_LIMIT) : [];
  const events = [];
  for (const item of items) {
    const part = item?.part;
    if (!item?.recommendationId || !part?.id || !part?.category) continue;
    events.push({
      eventType: 'IMPRESSION',
      sourceSurface: 'HOME_RECOMMENDED_PARTS',
      recommendationId: item.recommendationId,
      partId: part.id,
      category: part.category,
      rankPosition: item.rankPosition,
      idempotencyKey: `home-impression-${item.recommendationId}`
    });
  }
  if (!events.length) return;
  const eventResponse = http.post(`${baseUrl()}/api/recommendation-events/bulk/async`, JSON.stringify({ events }), {
    headers: authHeaders(token),
    tags: homeTags('recommended-part-impression', {
      endpoint: 'recommendation_events_bulk_async',
      visitor: 'authenticated',
      eventType: 'IMPRESSION'
    }),
    timeout: requestTimeout()
  });
  check(eventResponse, {
    'home impressions queued': (res) => res.status === 202
  });
}

function homeTags(phase, extra = {}) {
  return {
    flow: 'home',
    variant: __ENV.K6_VARIANT || __ENV.CACHE_MODE || 'baseline',
    phase,
    ...extra
  };
}

function hasObjectField(response, field) {
  const body = parseJson(response);
  return Boolean(body && typeof body[field] === 'object' && !Array.isArray(body[field]));
}

function parseJson(response) {
  try {
    return JSON.parse(response.body || '{}');
  } catch (_) {
    return null;
  }
}

function boolEnv(name, defaultValue) {
  const raw = __ENV[name];
  if (raw === undefined || raw === null || raw === '') return defaultValue;
  return ['1', 'true', 'yes', 'on'].includes(String(raw).trim().toLowerCase());
}

function sleepBeforeHomeEvent() {
  const baseDelayMs = Math.max(0, HOME_EVENT_DELAY_MS);
  const jitterMs = Math.max(0, HOME_EVENT_JITTER_MS);
  const delayMs = baseDelayMs + Math.random() * jitterMs;
  if (delayMs > 0) {
    sleep(delayMs / 1000);
  }
}
