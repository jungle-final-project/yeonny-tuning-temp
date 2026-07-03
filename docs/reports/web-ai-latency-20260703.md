# 웹 체감 AI Latency 리포트

- 측정 기준: Docker live web + API에서 실제 챗봇 입력을 전송한 사용자 체감 시간
- 정책: 자동 route/action은 현상 유지, public API 응답 shape 변경 없음
- 전역 기준: 단일 케이스 5초 초과 0건

## 그룹 요약

| group | cases | successRate | avgMs | avgSec | p95Ms | p95Sec | maxMs | maxSec | thresholdMs | thresholdSec | failed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| FAST_LOCAL_ROUTE | 8 | 100.0% | 309 | 0.31 | 319 | 0.32 | 319 | 0.32 | 1000 | 1.00 | 0 |
| FAST_SERVER_ROUTE | 12 | 100.0% | 425 | 0.42 | 439 | 0.44 | 439 | 0.44 | 1500 | 1.50 | 0 |
| DRAFT_ACTION | 12 | 100.0% | 417 | 0.42 | 422 | 0.42 | 422 | 0.42 | 2000 | 2.00 | 0 |
| DETERMINISTIC_RECOMMEND | 16 | 100.0% | 424 | 0.42 | 439 | 0.44 | 439 | 0.44 | 3000 | 3.00 | 0 |
| LLM_FULL_COMPLEX | 12 | 100.0% | 421 | 0.42 | 428 | 0.43 | 428 | 0.43 | 5000 | 5.00 | 0 |

## 상세 결과

| id | group | ok | totalMs | totalSec | assistantMs | buildChatMs | actionMs | navigationMs | draftMs | answerType | actions | route | errors |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- | --- |
| local-001 | FAST_LOCAL_ROUTE | PASS | 311 | 0.31 | 57 | - | - | 59 | - | - | - | - | - |
| local-002 | FAST_LOCAL_ROUTE | PASS | 311 | 0.31 | 49 | - | - | 50 | - | - | - | - | - |
| local-003 | FAST_LOCAL_ROUTE | PASS | 303 | 0.30 | 50 | - | - | 51 | - | - | - | - | - |
| local-004 | FAST_LOCAL_ROUTE | PASS | 309 | 0.31 | 52 | - | - | 52 | - | - | - | - | - |
| local-005 | FAST_LOCAL_ROUTE | PASS | 306 | 0.31 | 50 | - | - | 51 | - | - | - | - | - |
| local-006 | FAST_LOCAL_ROUTE | PASS | 306 | 0.31 | 50 | - | - | 51 | - | - | - | - | - |
| local-007 | FAST_LOCAL_ROUTE | PASS | 303 | 0.30 | 50 | - | - | 51 | - | - | - | - | - |
| local-008 | FAST_LOCAL_ROUTE | PASS | 319 | 0.32 | 63 | - | 62 | 64 | - | - | - | - | - |
| server-exact-001 | FAST_SERVER_ROUTE | PASS | 427 | 0.43 | 164 | 6 | 50 | 166 | - | GENERAL | OPEN_ROUTE | /parts/a75d6544-2296-4c4c-a7cd-64596e66f6d7 | - |
| server-exact-002 | FAST_SERVER_ROUTE | PASS | 430 | 0.43 | 168 | 11 | 46 | 170 | - | GENERAL | OPEN_ROUTE | /parts/a2f9d478-b368-4c16-9048-4a064828c5c1 | - |
| server-exact-003 | FAST_SERVER_ROUTE | PASS | 419 | 0.42 | 158 | 9 | 46 | 160 | - | GENERAL | OPEN_ROUTE | /parts/09f39d2a-de00-4cbc-bd1a-241e9790111d | - |
| server-exact-004 | FAST_SERVER_ROUTE | PASS | 424 | 0.42 | 162 | 9 | 46 | 164 | - | GENERAL | OPEN_ROUTE | /parts/47c8753d-4544-4322-9aab-c175531eceeb | - |
| server-filter-001 | FAST_SERVER_ROUTE | PASS | 419 | 0.42 | 154 | 11 | 46 | 156 | - | GENERAL | OPEN_ROUTE | /self-quote?category=GPU&q=5090 | - |
| server-filter-002 | FAST_SERVER_ROUTE | PASS | 423 | 0.42 | 163 | 10 | 47 | 165 | - | GENERAL | OPEN_ROUTE | /self-quote?category=CPU&q=9950X3D | - |
| server-filter-003 | FAST_SERVER_ROUTE | PASS | 429 | 0.43 | 169 | 9 | 49 | 171 | - | GENERAL | OPEN_ROUTE | /self-quote?category=MOTHERBOARD&q=MSI+%EB%B3%B4%EB%93%9C | - |
| server-filter-004 | FAST_SERVER_ROUTE | PASS | 418 | 0.42 | 155 | 8 | 47 | 157 | - | GENERAL | OPEN_ROUTE | /self-quote?category=CASE&q=%EB%A6%AC%EC%95%88%EB%A6%AC+%EC%BC%80%EC%9D%B4%EC%8A%A4 | - |
| server-filter-005 | FAST_SERVER_ROUTE | PASS | 439 | 0.44 | 167 | 10 | 46 | 173 | - | GENERAL | OPEN_ROUTE | /self-quote?category=RAM&q=DDR5+%EB%9E%A8 | - |
| server-filter-006 | FAST_SERVER_ROUTE | PASS | 428 | 0.43 | 166 | 9 | 48 | 168 | - | GENERAL | OPEN_ROUTE | /self-quote?category=STORAGE&q=NVME+SSD | - |
| server-filter-007 | FAST_SERVER_ROUTE | PASS | 419 | 0.42 | 159 | 9 | 49 | 160 | - | GENERAL | OPEN_ROUTE | /self-quote?category=PSU&q=1000W+%ED%8C%8C%EC%9B%8C | - |
| server-filter-008 | FAST_SERVER_ROUTE | PASS | 423 | 0.42 | 163 | 9 | 47 | 165 | - | GENERAL | OPEN_ROUTE | /self-quote?category=COOLER&q=%EC%88%98%EB%9E%AD+%EC%BF%A8%EB%9F%AC | - |
| draft-001 | DRAFT_ACTION | PASS | 422 | 0.42 | 158 | 13 | 41 | - | 162 | PART | REMOVE_DRAFT_PART | - | - |
| draft-002 | DRAFT_ACTION | PASS | 421 | 0.42 | 160 | 12 | 45 | - | 163 | PART | UPDATE_DRAFT_QUANTITY | - | - |
| draft-003 | DRAFT_ACTION | PASS | 417 | 0.42 | 157 | 13 | 43 | - | 161 | PART | REPLACE_DRAFT_PART | - | - |
| draft-004 | DRAFT_ACTION | PASS | 419 | 0.42 | 158 | 12 | 45 | - | 162 | PART | REPLACE_DRAFT_PART | - | - |
| draft-005 | DRAFT_ACTION | PASS | 416 | 0.42 | 155 | 13 | 44 | - | 160 | PART | REPLACE_DRAFT_PART | - | - |
| draft-006 | DRAFT_ACTION | PASS | 414 | 0.41 | 152 | 14 | 42 | - | 156 | PART | REPLACE_DRAFT_PART | - | - |
| draft-007 | DRAFT_ACTION | PASS | 415 | 0.41 | 153 | 11 | 45 | - | 158 | PART | REPLACE_DRAFT_PART | - | - |
| draft-008 | DRAFT_ACTION | PASS | 417 | 0.42 | 156 | 13 | 46 | - | 160 | PART | REPLACE_DRAFT_PART | - | - |
| draft-009 | DRAFT_ACTION | PASS | 417 | 0.42 | 156 | 11 | 47 | - | 160 | PART | REPLACE_DRAFT_PART | - | - |
| draft-010 | DRAFT_ACTION | PASS | 412 | 0.41 | 154 | 12 | 45 | - | 158 | PART | UPDATE_DRAFT_QUANTITY | - | - |
| draft-011 | DRAFT_ACTION | PASS | 418 | 0.42 | 161 | 13 | 44 | - | 166 | PART | REPLACE_DRAFT_PART | - | - |
| draft-012 | DRAFT_ACTION | PASS | 413 | 0.41 | 156 | 10 | 46 | - | 160 | PART | REPLACE_DRAFT_PART | - | - |
| det-build-001 | DETERMINISTIC_RECOMMEND | PASS | 414 | 0.41 | 156 | 8 | 46 | - | - | BUDGET | - | - | - |
| det-build-002 | DETERMINISTIC_RECOMMEND | PASS | 419 | 0.42 | 160 | 10 | 47 | - | - | BUDGET | - | - | - |
| det-build-003 | DETERMINISTIC_RECOMMEND | PASS | 424 | 0.42 | 164 | 10 | 47 | - | - | BUDGET | - | - | - |
| det-build-004 | DETERMINISTIC_RECOMMEND | PASS | 425 | 0.42 | 166 | 9 | 48 | - | - | BUDGET | - | - | - |
| det-build-005 | DETERMINISTIC_RECOMMEND | PASS | 416 | 0.42 | 155 | 8 | 48 | - | - | BUDGET | - | - | - |
| det-build-006 | DETERMINISTIC_RECOMMEND | PASS | 439 | 0.44 | 161 | 10 | 47 | - | - | BUDGET | - | - | - |
| det-build-007 | DETERMINISTIC_RECOMMEND | PASS | 427 | 0.43 | 166 | 10 | 47 | - | - | BUDGET | - | - | - |
| det-build-008 | DETERMINISTIC_RECOMMEND | PASS | 432 | 0.43 | 169 | 6 | 51 | - | - | BUDGET | - | - | - |
| det-build-009 | DETERMINISTIC_RECOMMEND | PASS | 422 | 0.42 | 161 | 9 | 47 | - | - | BUDGET | - | - | - |
| det-build-010 | DETERMINISTIC_RECOMMEND | PASS | 432 | 0.43 | 169 | 10 | 48 | - | - | BUDGET | - | - | - |
| det-build-011 | DETERMINISTIC_RECOMMEND | PASS | 418 | 0.42 | 158 | 10 | 47 | - | - | BUDGET | - | - | - |
| det-build-012 | DETERMINISTIC_RECOMMEND | PASS | 422 | 0.42 | 163 | 10 | 47 | - | - | BUDGET | - | - | - |
| det-build-013 | DETERMINISTIC_RECOMMEND | PASS | 430 | 0.43 | 168 | 9 | 48 | - | - | BUDGET | - | - | - |
| det-part-001 | DETERMINISTIC_RECOMMEND | PASS | 420 | 0.42 | 158 | 8 | 48 | - | - | PART | ADD_PART_TO_DRAFT | - | - |
| det-part-002 | DETERMINISTIC_RECOMMEND | PASS | 422 | 0.42 | 162 | 10 | 46 | - | - | PART | ADD_PART_TO_DRAFT | - | - |
| det-part-003 | DETERMINISTIC_RECOMMEND | PASS | 427 | 0.43 | 166 | 8 | 48 | - | - | PART | ADD_PART_TO_DRAFT | - | - |
| llm-complex-001 | LLM_FULL_COMPLEX | PASS | 415 | 0.41 | 158 | 7 | 50 | - | - | BUDGET | - | - | - |
| llm-complex-002 | LLM_FULL_COMPLEX | PASS | 421 | 0.42 | 161 | 10 | 48 | - | - | BUDGET | - | - | - |
| llm-complex-003 | LLM_FULL_COMPLEX | PASS | 423 | 0.42 | 163 | 9 | 46 | - | - | BUDGET | - | - | - |
| llm-complex-004 | LLM_FULL_COMPLEX | PASS | 428 | 0.43 | 165 | 7 | 47 | - | - | BUDGET | - | - | - |
| llm-complex-005 | LLM_FULL_COMPLEX | PASS | 422 | 0.42 | 160 | 10 | 47 | - | - | BUDGET | - | - | - |
| llm-complex-006 | LLM_FULL_COMPLEX | PASS | 426 | 0.43 | 167 | 9 | 46 | - | - | BUDGET | - | - | - |
| llm-complex-007 | LLM_FULL_COMPLEX | PASS | 421 | 0.42 | 159 | 9 | 49 | - | - | BUDGET | - | - | - |
| llm-complex-008 | LLM_FULL_COMPLEX | PASS | 407 | 0.41 | 151 | 11 | 31 | - | - | BUDGET | - | - | - |
| llm-complex-009 | LLM_FULL_COMPLEX | PASS | 427 | 0.43 | 168 | 9 | 49 | - | - | BUDGET | - | - | - |
| llm-complex-010 | LLM_FULL_COMPLEX | PASS | 418 | 0.42 | 159 | 9 | 49 | - | - | BUDGET | - | - | - |
| llm-complex-011 | LLM_FULL_COMPLEX | PASS | 421 | 0.42 | 160 | 9 | 47 | - | - | BUDGET | - | - | - |
| llm-complex-012 | LLM_FULL_COMPLEX | PASS | 427 | 0.43 | 168 | 8 | 48 | - | - | BUDGET | - | - | - |

## 해석

- 이 리포트는 API 직접 호출 benchmark가 아니라 브라우저 입력, 자동 action, route 이동, draft 갱신까지 포함한 웹 체감 기준이다.
- FAST_LOCAL_ROUTE는 프론트 shortcut 품질을, FAST_SERVER_ROUTE는 서버 route resolver 품질을 본다.
- DRAFT_ACTION은 자동 장바구니 action이 실제 quote draft API와 화면 상태까지 마무리되는 시간을 본다.
- LLM_FULL_COMPLEX는 fast path가 처리하지 않는 복합 의도 요청이 5초 안에 끝나는지 확인한다.
