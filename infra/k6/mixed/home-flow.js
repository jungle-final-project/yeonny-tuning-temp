import http from 'k6/http';
import { check, sleep } from 'k6';
import { login, pickByPareto } from '../util/general-helper.js';
import { getHomeRcmds, getHomeComp, getPartsByCategory, getPartDetail, recordEvent} from '../util/home-url.js';

/* 시나리오: 두 개의 주요 분기가 존재한다
   1. 추천 카드 탐색 루트
   2. 일반 부품 탐색 루트 */
export const options = {
    scenarios: {
        /* 추후 구현 필요! */
    }
};

/* 1회성 로그인 진행 */
export function setup() {
    return {
        noneToken: login(
            'http://localhost:8081',
            __ENV.TEST_EMAIL,
            __ENV.TEST_PASSWORD
        )
    };
}

/* 시작 홈 페이지 */
export function homePage(data) {
    const token =
    __ENV.CACHE_MODE === 'caffeine'
        ? data.caffeineToken
        : data.noneToken;

    /* 전체 추천 불러오기 
       부품 추천 불러오기 */
    const getCompRes = getHomeComp(token);
    const getRcmdRes = getHomeRcmds(token);
}

/* 추가 함수 필요! */