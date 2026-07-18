import http from 'k6/http';
import { authHeaders, baseUrl, commonTags } from './general-helper.js';

/* AI 챗봇 상담 요청 URL */
export function sendMessage(
    token,
    message,
    currentBuilds = [],
    clarificationContext = null,
    step = 'chat'
) {
    const payload = {
        message,
        currentBuilds,
        uiContext: {
        surface: 'HOME',
        capabilities: [],
        },
    };

    /* 방어코드 */
    if (clarificationContext) {
        payload.clarificationContext = clarificationContext;
    }

    return http.post(
        `${baseUrl()}/api/ai/build-chat`,
        JSON.stringify(payload),
        {
            headers: {
                ...authHeaders(token),
                'X-BuildGraph-AI-Mode': 'MOCK',
                'X-BuildGraph-Test-Key': __ENV.AI_TEST_KEY,
            },
            tags: commonTags('ai-build-chat', {
                flow: 'ai-chat',
                step,
                endpoint: 'ai_build_chat',
            }),
            timeout: __ENV.AI_REQUEST_TIMEOUT || '60s',
        }
    );
}