import http from 'k6/http';
import { authHeaders } from './quote-helper.js';

/* 테스트 대상 url */
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

/* 부품을 추가하는 url */
export function addPart(token, partId, quantity = 1) {
    return http.put(
        `${BASE_URL}/api/quote-drafts/current/items/${partId}`,
        JSON.stringify({ quantity }),
        {
            headers: authHeaders(token),
            tags: { phase: 'add-part' },
        }
    );
}

/* 부품 리스트를 불러오는 url */
export function getParts(token, category) {
    return http.get(
        `${BASE_URL}/api/parts?category=${category}&page=0&size=10&sort=price_asc&compatibilitySource=QUOTE_DRAFT_CURRENT`,
        {
            headers: authHeaders(token),
            tags: { step: category.toLowerCase(), phase: 'candidate-list' },
        }
    );
}

/* 5가지 검증은 전격 실행하는 url: 하드코딩 됨 */
export function checkAllConditions(token, category) {
    return http.post(
        `${BASE_URL}/api/build-graphs/resolve`,
        JSON.stringify({
            source: 'QUOTE_DRAFT_CURRENT',
            view: 'FOCUSED',
            focus: {
                mode: 'PART_IMPACT',
                category,
            },
        }),
        {
        headers: authHeaders(token),
        tags: { step: category.toLowerCase(), phase: 'graph-resolve' },
        }
    );
}