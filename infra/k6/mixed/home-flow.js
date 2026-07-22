import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { loginForVu } from '../util/general-helper.js';
import { getHomeRcmds, getHomeComp, getPartsByCategory, getPartDetail } from '../util/home-url.js';
import { addingPart } from '../core/self-quote-flow.js';

const accessTokens = {};
const CATEGORIES = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER'];

export const options = {
    scenarios: {
        home_to_self_quote: {
            executor: 'constant-vus',
            exec: 'homePage',
            vus: Number(__ENV.HOME_VUS || '5'),
            duration: __ENV.HOME_DURATION || '90s',
            env: {
                BASE_URL: __ENV.BASE_URL || 'http://localhost:8080',
                K6_FLOW: 'home-to-self-quote',
            },
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500'],
    },
};

function tokenForVu() {
    const accountPoolSize = Number(__ENV.TEST_USER_COUNT || '500');
    const accountIndex = ((exec.vu.idInTest - 1) % accountPoolSize) + 1;
    if (!accessTokens[accountIndex]) {
        accessTokens[accountIndex] = loginForVu(accountIndex);
    }
    return accessTokens[accountIndex];
}

export function homePage() {
    const token = tokenForVu();
    const partsResponse = getHomeComp(token);
    const recommendationsResponse = getHomeRcmds(token);

    check(partsResponse, { '홈 일반 부품 200': (response) => response.status === 200 });
    check(recommendationsResponse, { '홈 추천 부품 200': (response) => response.status === 200 });

    if (Math.random() < 0.3) {
        for (const category of CATEGORIES) {
            addingPart(token, category);
        }
        return;
    }

    const category = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
    const page = Math.floor(Math.pow(Math.random(), 0.7) * 4);
    const categoryResponse = getPartsByCategory(token, category, page);
    check(categoryResponse, { '카테고리 탐색 200': (response) => response.status === 200 });

    if (categoryResponse.status === 200) {
        const items = categoryResponse.json('items') || [];
        if (items.length > 0) {
            const part = items[Math.floor(Math.random() * items.length)];
            const detailResponse = getPartDetail(token, part.id);
            check(detailResponse, { '부품 상세 200': (response) => response.status === 200 });
        }
    }

    sleep(0.5 + Math.random());
}