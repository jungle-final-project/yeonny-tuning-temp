import http from 'k6/http';
import { authHeaders, baseUrl, commonTags, requestTimeout } from './general-helper.js';

/* 부품 리스트를 불러오는 url */
export function getParts(token, category, page, size = 10) {
  return http.get(`${baseUrl()}/api/parts?category=${category}&page=${page}&size=${size}&sort=price_asc&compatibilitySource=QUOTE_DRAFT_CURRENT`, {
    headers: authHeaders(token),
    tags: commonTags('candidate-list', { step: category.toLowerCase(), endpoint: 'parts' }),
    timeout: requestTimeout(),
  });
}

/* 부품을 추가하는 url */
export function addPart(token, partId, category, quantity = 1) {
  return http.put(`${baseUrl()}/api/quote-drafts/current/items/${partId}`, JSON.stringify({ quantity }), {
    headers: authHeaders(token),
    tags: commonTags('add-part', { step: category.toLowerCase(), endpoint: 'quote_draft_item' }),
    timeout: requestTimeout(),
  });
}

/* 5가지 검증은 전격 실행하는 url: 하드코딩 됨 */
export function resolveBuildGraph(token, category) {
  return http.post(`${baseUrl()}/api/build-graphs/resolve`, JSON.stringify({
    source: 'QUOTE_DRAFT_CURRENT',
    view: 'FOCUSED',
    focus: { mode: 'PART_IMPACT', category },
  }), {
    headers: authHeaders(token),
    tags: commonTags('graph-resolve', { step: category.toLowerCase(), endpoint: 'build_graph' }),
    timeout: requestTimeout(),
  });
}
