import { check } from 'k6';
import http from 'k6/http';
import exec from 'k6/execution';
import { sendMessage } from '../util/ai-chat-url.js';
import { credentialsForVu } from '../util/general-helper.js';

/* 시나리오는 self-quote-..-breakpoint + 추가 부하 및 튜닝
   : target => 초당 'exec' 호출 빈도 */
export const options = {
    scenarios: {
        breakpoint_none: {
            executor: 'ramping-arrival-rate',
            exec: 'aiChatFlow',

            startRate: 1,
            timeUnit: '1s',
            preAllocatedVUs: 100,
            maxVUs: 1000,

            stages: [
                { target: 2, duration: '10s' },
                { target: 4, duration: '10s' },
                { target: 8, duration: '10s' },
                { target: 16, duration: '10s' },
                { target: 32, duration: '10s' },
                { target: 64, duration: '10s' },
                { target: 64, duration: '10s' },
                { target: 96, duration: '10s' },
                { target: 128, duration: '0s' },
                { target: 256, duration: '10s' },
                { target: 512, duration: '10s' },
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

/* 실행 함수: ai 채팅 시나리오 - 목업 수행 */
export function aiChatFlow(data) {
    /* 로그인 수행 => 토큰 취득 */
    const tokenIndex = (exec.vu.idInTest - 1) % data.tokens.length;
    const token = data.tokens[tokenIndex];

    /* 메시지에 전송되는 기본 객체 */
    const context = {
        currentBuilds: [],
        clarificationContext: null,
    };

    /* 커스텀 메시지들 */
    const firstMessage = '중학생 300만원 짜리 컴퓨터 추천해줘';
    const secondMessage = '이번에는 너무 가격이 낮은 거 같아. 너가 생각하는, 사이버펑크 60fps 방어하기에 좋은 견적은 뭐야?';
    const selectedMessage = '400만원 게이밍 PC 추천해줘';

    /* 순서대로 1차 => 2차 => 3차 메시지 */
    if (!sendChat(
        token,
        firstMessage,
        'initial-recommend',
        '1차: 최초 견적 추천',
        context
    )) return;

    if (!sendChat(
        token,
        secondMessage,
        'budget-follow-up',
        '2차: 예산 수정 피드백',
        context
    )) return;

    if (!sendChat(
        token,
        selectedMessage,
        'quick-reply-recommend',
        '3차: 추천안 선택',
        context
    )) return;
}

/* 컴포넌트 함수: 챗 메시지 전송 및 응답 검사 */
function sendChat(token, message, phase, label, context) {
    const response = sendMessage(
        token,
        message,
        context.currentBuilds,
        context.clarificationContext,
        phase
    );

    const body = checkChat(response, label);
    if (!body) return null;

    if (body.builds?.length) {
        context.currentBuilds = body.builds;
    }

    context.clarificationContext = clarificationContextFrom(body);
    return body;
}

/* 보조함수: 맥락을 쌓기 기능 */
function clarificationContextFrom(responseBody) {
    const originalMessage = responseBody?.clarification?.originalMessage;

    return originalMessage
        ? { originalMessage }
        : null;
}

/* 보조함수: 응답 체크 기능 */
function checkChat(response, step) {
    const passed = check(response, {
        [`${step} 200`]: (res) => res.status === 200,
    });

    return passed ? response.json() : null;
}