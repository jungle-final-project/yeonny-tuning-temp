import http from 'k6/http';
import { check, sleep } from 'k6';
import { login, pickByPareto } from './util/quote-helper.js';
import { addPart, getParts, checkAllConditions } from './util/quote-url.js';

/* 테스트 설정값: 시나리오
    : 시나리오 명 
        - 가상 유저 수 유지
        - 가상 유저 수
        - 진행 시간 */
export const options = {
    scenarios: {
        /* 캐시 없는 상태로 테스트 */
        cache_none: {
            executor: 'constant-vus',
            exec: 'selfQuoteFlow',
            vus: 5,
            duration: '90s',
            startTime: '0s',
            env: {
                BASE_URL: 'http://localhost:8081',
            },
            tags: {
                cache: 'none',
            },
        },
        /* 캐시 있는 상태로 테스트 */
        cache_caffeine: {
            executor: 'constant-vus',
            exec: 'selfQuoteFlow',
            vus: 5,
            duration: '90s',
            startTime: '95s',
            env: {
                BASE_URL: 'http://localhost:8082',
            },
            tags: {
                cache: 'caffeine',
            },
        },
    }
};

/* 가상 사용자 요청 흐름
   로그인 => self-quote 이동 => 1..8까지 부품 담기(여기서 Tool 호출) */
export function selfQuoteFlow() {
    const token = login();

    addingPart(token, 'CPU');
    addingPart(token, 'MOTHERBOARD');
    addingPart(token, 'RAM');
    addingPart(token, 'GPU');
    addingPart(token, 'STORAGE');
    addingPart(token, 'PSU');
    addingPart(token, 'CASE');
    addingPart(token, 'COOLER');
}

/* 각 단계 수행 함수들 */
export function addingPart(token, partCategory){
    /* 부품 후보 리스트 조회
       + 현재 draft 조회
       + 후보별 호환 검증 */
    const listRes = getParts(token, partCategory);
    check(listRes, { [`${partCategory} 후보 리스트 200`]: (res) => res.status === 200, });
    if (listRes.status !== 200) return;

    /* 가중치 넣어서 부품 하나 선택 */
    const body = JSON.parse(listRes.body);
    const picked = pickByPareto(body.items);
    const PART_ID = picked.id;

    /* 이 중 부품 하나 선택 => draft(장바구니)에 들어감 */
    const putRes = addPart(token, PART_ID);
    check(putRes, { [`${partCategory} 선택됨 200`]: (res) => res.status === 200, });
    if (putRes.status !== 200) return;

    /* 부품이 슬롯에 들어감 => 의존성 그래프 검증 */
    const graphRes = checkAllConditions(token, partCategory);
    check(graphRes, { [`${partCategory} 의존성 확인 200`]: (res) => res.status === 200, });

    sleep(0.3);
}


