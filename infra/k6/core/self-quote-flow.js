import http from 'k6/http';
import { check, sleep } from 'k6';
import { login } from '../util/general-helper.js';
import { addPart, getParts, checkAllConditions } from '../util/quote-url.js';

/* 테스트 설정값: 시나리오
    : 시나리오 명 
        - 캐싱 없을 시
        - 캐싱 수행 시 */
export const options = {
    scenarios: {
        /* 캐시 없는 상태로 테스트 */
        cache_none: {
            executor: 'ramping-arrival-rate',
            exec: 'selfQuoteFlow',

            startRate: 2,
            timeUnit: '1s',
            preAllocatedVUs: 100,
            maxVUs: 500,

            stages: [
                { target: 2, duration: '5s' },    // 약 50 HTTP RPS
                { target: 4, duration: '5s' },    // 약 100 HTTP RPS
                { target: 8, duration: '5s' },    // 약 200 HTTP RPS
                { target: 12, duration: '5s' },   // 약 300 HTTP RPS
                { target: 20, duration: '10s' },  // 약 500 HTTP RPS까지 증가
                { target: 20, duration: '1m' },   // 약 500 HTTP RPS 유지
                { target: 2, duration: '30s' },   // 부하 감소
            ],

            startTime: '0s',
            env: {
                BASE_URL: 'http://localhost:8081',
                CACHE_MODE: 'none',
            },
            tags: {
                cache: 'none',
            },
        },
        /* 캐시 있는 상태로 테스트 */
        cache_caffeine: {
            executor: 'ramping-arrival-rate',
            exec: 'selfQuoteFlow',

            startRate: 2,
            timeUnit: '1s',
            preAllocatedVUs: 100,
            maxVUs: 500,

            stages: [
                { target: 2, duration: '5s' },    // 약 50 HTTP RPS
                { target: 4, duration: '5s' },    // 약 100 HTTP RPS
                { target: 8, duration: '5s' },    // 약 200 HTTP RPS
                { target: 12, duration: '5s' },   // 약 300 HTTP RPS
                { target: 20, duration: '10s' },  // 약 500 HTTP RPS까지 증가
                { target: 20, duration: '1m' },   // 약 500 HTTP RPS 유지
                { target: 2, duration: '30s' },   // 부하 감소
            ],

            startTime: '155s',
            env: {
                BASE_URL: 'http://localhost:8082',
                CACHE_MODE: 'caffeine',
            },
            tags: {
                cache: 'caffeine',
            }
        },
    }
};

/* 1회성 로그인 진행 */
export function setup() {
    return {
        noneToken: login(
            'http://localhost:8081',
            __ENV.TEST_EMAIL,
            __ENV.TEST_PASSWORD
        ),

        caffeineToken: login(
            'http://localhost:8082',
            __ENV.TEST_EMAIL,
            __ENV.TEST_PASSWORD
        ),
    };
}

/* 가상 사용자 요청 흐름
   로그인 => self-quote 이동 => 1..8까지 부품 담기(여기서 Tool 호출)
   안자 data는 setup이 반환한 값 */
export function selfQuoteFlow(data) {
    const token =
    __ENV.CACHE_MODE === 'caffeine'
        ? data.caffeineToken
        : data.noneToken;

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
    /* 스크롤링을 하여 특정 부품을 선택 */
    const PART_ID = scrollPage(token, partCategory);

    /* 이 중 부품 하나 선택 => draft(장바구니)에 들어감 */
    const putRes = addPart(token, PART_ID);
    check(putRes, { [`${partCategory} 선택됨 200`]: (res) => res.status === 200, });
    if (putRes.status !== 200) return;

    /* 부품이 슬롯에 들어감 => 의존성 그래프 검증 */
    const graphRes = checkAllConditions(token, partCategory);
    check(graphRes, { [`${partCategory} 의존성 확인 200`]: (res) => res.status === 200, });

    sleep(0.3);
}

/* 페이지 넘기기 시나리오 함수 */
export function scrollPage(token, partCategory){
    /* 첫 페이지 조회하여 총 페이지 수 구하기 */
    const firstPageRes = getParts(token, partCategory, 0);
    check(firstPageRes, { [`${partCategory} 후보 리스트 200`]: (res) => res.status === 200, });
    if (firstPageRes.status !== 200) return;
    
    /* 총 페이지 수 구하기
       : json 파싱
       : 선택할 객체 index 구하기 */
    const firstBody = JSON.parse(firstPageRes.body);
    const totalPages = Number(firstBody.total);
    const pickIndex = Math.min(
        totalPages - 1,
        Math.floor(Math.pow(Math.random(), 0.8) * firstBody.total)
    );
    
    /* 객체 index 기반 타겟 페이지 + 마지막 페이지 index 산출 */
    const targetPage = Math.floor(pickIndex / 10);
    const indexInPage = pickIndex % 10;

    /* 페이지 스크롤링(넘기기) => 마지막 페이지 까지
       : 첫 페이지라면 for 문 수행 안됨 */
    let pageRes = firstPageRes;

    for(let i = 1; i <= targetPage; i++){
        /* 스크롤 지연 시간 추가 */
        sleep(0.3 + Math.random() * 0.7);

        pageRes = getParts(token, partCategory, i);
        check(pageRes, { [`${partCategory} 후보 리스트 200`]: (res) => res.status === 200, });
        if (pageRes.status !== 200) return;
    }

    /* 타겟 부품 선택 => ID 추출 */
    const body = JSON.parse(pageRes.body);
    const pickedPart = body.items[indexInPage];
    
    return pickedPart.id;
}


