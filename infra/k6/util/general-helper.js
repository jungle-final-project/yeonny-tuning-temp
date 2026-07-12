import http from 'k6/http';

/* 테스트 대상 url */
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

/* helper 함수들
   1. 인증 파싱
   2. 가중 랜덤 선택 */
export function authHeaders(token) {
    return {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
    };
}

export function pickByPareto(items) {
    if (!items || items.length === 0) {
        throw new Error('후보 부품이 없습니다.');
    }

    const hotCount = Math.max(1, Math.ceil(items.length * 0.2));
    const hotItems = items.slice(0, hotCount);
    const coldItems = items.slice(hotCount);

    const pool = Math.random() < 0.8 || coldItems.length === 0
        ? hotItems
        : coldItems;

    return pool[Math.floor(Math.random() * pool.length)];
}

/* 1. 로그인 함수 */
export function login(
    targetBaseUrl = baseUrl(),
    email = __ENV.TEST_EMAIL,
    password = __ENV.TEST_PASSWORD
) {
    const res = http.post(
        `${targetBaseUrl}/api/auth/login`,
        JSON.stringify({
            email,
            password,
        }),
        {
            headers: {
                'Content-Type': 'application/json',
            },
            tags: {
                phase: 'setup-login',
            },
        }
    );

    if (res.status !== 200) {
        throw new Error(
            `로그인 실패: url=${targetBaseUrl}, status=${res.status}`
        );
    }

    return JSON.parse(res.body).accessToken;
}
