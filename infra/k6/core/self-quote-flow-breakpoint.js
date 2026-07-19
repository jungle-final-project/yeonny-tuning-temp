import http from 'k6/http';
import exec from 'k6/execution';
import { addingPart } from './self-quote-flow-basic.js';
import { credentialsForVu } from '../util/general-helper.js';


export const options = {
    scenarios: {
        breakpoint_none: {
            executor: 'ramping-arrival-rate',
            exec: 'selfQuoteFlow',

            startRate: 1,
            timeUnit: '1s',
            preAllocatedVUs: 100,
            maxVUs: 1000,

            /* 각 단계를 진행: target은 "초당 요청 수"이다
               실제 RPS = 초당 iteration 수 × selfQuoteFlow 1회당 HTTP 요청 수 
               따라서.. target:2 ..~.. 50RPS */
            stages: [
                { target: 2, duration: '20s' },
                { target: 4, duration: '20s' },
                { target: 8, duration: '20s' },
                { target: 16, duration: '20s' },
                { target: 32, duration: '20s' },
                { target: 64, duration: '20s' },  // 병목 없을 시, 이론상 최소 1,536RPS
            ],

            env: {
                BASE_URL: __ENV.NONE_BASE_URL || 'http://localhost:8080',
                CACHE_MODE: 'none',
            },
            tags: {
                cache: 'none',
                test_type: 'breakpoint',
            },
        },
    },
    /* 실패 기준을 판별
       : 요청 실패율 1% 초과 시 실패, 15초 이후부터 발동됨
       : 전체 요청 95%가 500ms 내가 아니면 실패 => p95 >= 500 이 기준
       : 목표 요청률(target)에 도달하지 못하면 실패 */
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500'],
        dropped_iterations: ['count==0'],
    },
};

/* 로그인은 테스트 1회 = 1회로 한정 
   : 다만 가상 계정은 N개로 지정 */
export function setup() {
    const baseUrl = __ENV.NONE_BASE_URL || 'http://localhost:8080';
    const accountCount = Number(__ENV.TEST_USER_COUNT || '5');
    const tokens = [];

    for (let accountIndex = 1; accountIndex <= accountCount; accountIndex++) {
        const response = http.post(
            `${baseUrl}/api/auth/login`,
            JSON.stringify(credentialsForVu(accountIndex)),
            {
                headers: { 'Content-Type': 'application/json' },
            }
        );

        if (response.status !== 200) {
            throw new Error(
                `초기 로그인 실패: account=${accountIndex}, status=${response.status}`
            );
        }

        tokens.push(response.json('accessToken'));
    }

    return { tokens };
}

/* base에서 가져온 함수: addingPart을 사용 */
export function selfQuoteFlow(data) {
    const tokenIndex = (exec.vu.idInTest - 1) % data.tokens.length;
    const token = data.tokens[tokenIndex];

    addingPart(token, 'CPU');
    addingPart(token, 'MOTHERBOARD');
    addingPart(token, 'RAM');
    addingPart(token, 'GPU');
    addingPart(token, 'STORAGE');
    addingPart(token, 'PSU');
    addingPart(token, 'CASE');
    addingPart(token, 'COOLER');
}

