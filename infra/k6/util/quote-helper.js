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

export function login() {
    const res = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({
        email: __ENV.TEST_EMAIL,
        password: __ENV.TEST_PASSWORD,
        }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    return JSON.parse(res.body).accessToken;
}
