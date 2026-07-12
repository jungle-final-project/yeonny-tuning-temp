import http from 'k6/http';
import { authHeaders } from './general-helper.js';

/* 테스트 대상 url: 시나리오의 것을 자동 캐치 */
function baseUrl() {
    return __ENV.BASE_URL || 'http://localhost:8081';
}

/* 홈 전체 추천  */
export function getHomeComp(token, page = 0, size = 10) {
    return http.get(
        `${baseUrl()}/api/parts?page=${page}&size=${size}`,
        {
            headers: authHeaders(token),
            tags: { phase: 'part-list' },
        }
    );
}

/* 홈 부품 추천 */
export function getHomeRcmds(token, limit = 4) {
    return http.get(
        `${baseUrl()}/api/recommendations/home-parts?limit=${limit}`,
        {
            headers: authHeaders(token),
            tags: { phase: 'home-recommendations' },
        }
    );
}

/* 카테고리 별 부품 탐색 */
export function getPartsByCategory(
    token,
    category,
    page = 0,
    size = 10
) {
    return http.get(
        `${baseUrl()}/api/parts?category=${category}&page=${page}&size=${size}&sort=price_asc`,
        {
            headers: authHeaders(token),
            tags: {
                step: category.toLowerCase(),
                phase: 'category-parts',
            },
        }
    );
}

/* 부품 상세 조회 */
export function getPartDetail(token, partId) {
    return http.get(
        `${baseUrl()}/api/parts/${partId}`,
        {
            headers: authHeaders(token),
            tags: { phase: 'part-detail' },
        }
    );
}

/* 클릭 및 조회 기록 함수: POST.. 현재 Mock 엔드포인트(DB 접근X) */
export function recordEvent(
    token,
    eventType,
    recommendationId,
    partId,
    sessionId
) {
    return http.post(
        `${baseUrl()}/api/recommendation-events`,
        JSON.stringify({
            eventType,
            sourceSurface: 'HOME_RECOMMENDED_PARTS',
            recommendationId,
            partId,
            sessionId,
        }),
        {
            headers: authHeaders(token),
            tags: {
                phase: 'recommendation-event',
                event_type: eventType.toLowerCase(),
            },
        }
    );
}