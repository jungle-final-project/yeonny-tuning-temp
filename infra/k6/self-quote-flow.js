import http from 'k6/http';
import { check, sleep } from 'k6';
import { pickByPareto } from './util/quote-helper.js';
import { addPart, getParts, checkAllConditions } from './util/quote-url.js';

/* 테스트 설정값: 시나리오,  */
export const options = {
    scenarios: {
        smoke: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s'
        }
    }
};

/* 가상 사용자 요청 흐름
   로그인 => self-quote 이동 => 1..8까지 부품 담기(여기서 Tool 호출) */
export default function () {
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
    /* cpu 슬롯 하나 선택 => list 반환 */
    const listRes = getParts(token, partCategory);
    check(listRes, { [`${partCategory} 후보 리스트 200`]: (res) => res.status === 200, });
    if (listRes.status !== 200) return;

    /* 가중치 넣어서 부품 하나 선택 */
    const body = JSON.parse(listRes.body);
    const picked = pickByPareto(body.items);
    const PART_ID = picked.id;

    /* 이 중 cpu 하나 선택: url 접속 */
    const putRes = addPart(token, PART_ID);
    check(putRes, { [`${partCategory} 선택됨 200`]: (res) => res.status === 200, });
    if (putRes.status !== 200) return;

    /* cpu 가 슬롯에 들어감 => 의존성 그래프 검증 */
    const graphRes = checkAllConditions(token, partCategory);
    check(graphRes, { [`${partCategory} 의존성 확인 200`]: (res) => res.status === 200, });

    sleep(0.3);
}


