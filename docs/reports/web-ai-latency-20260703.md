# 웹 체감 AI Latency 리포트

- 측정 기준: Docker live web + API에서 실제 챗봇 입력을 전송한 사용자 체감 시간
- 정책: 자동 route/action은 현상 유지, public API 응답 shape 변경 없음
- 전역 기준: 단일 케이스 5초 초과 0건

## 그룹 요약

| group | cases | successRate | avgMs | avgSec | p95Ms | p95Sec | maxMs | maxSec | thresholdMs | thresholdSec | failed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| FAST_LOCAL_ROUTE | 8 | 100.0% | 756 | 0.76 | 888 | 0.89 | 888 | 0.89 | 1000 | 1.00 | 0 |
| FAST_SERVER_ROUTE | 12 | 100.0% | 717 | 0.72 | 776 | 0.78 | 776 | 0.78 | 1500 | 1.50 | 0 |
| DRAFT_ACTION | 12 | 100.0% | 715 | 0.71 | 734 | 0.73 | 734 | 0.73 | 2000 | 2.00 | 0 |
| DETERMINISTIC_RECOMMEND | 16 | 100.0% | 713 | 0.71 | 819 | 0.82 | 819 | 0.82 | 3000 | 3.00 | 0 |
| LLM_FULL_COMPLEX | 12 | 100.0% | 699 | 0.70 | 729 | 0.73 | 729 | 0.73 | 5000 | 5.00 | 0 |

## 상세 결과

| id | group | ok | totalMs | totalSec | assistantMs | buildChatMs | actionMs | navigationMs | draftMs | answerType | actions | route | errors |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- | --- |
| local-001 | FAST_LOCAL_ROUTE | PASS | 756 | 0.76 | - | - | 121 | 503 | - | - | - | - | - |
| local-002 | FAST_LOCAL_ROUTE | PASS | 697 | 0.70 | - | - | 64 | 439 | - | - | - | - | - |
| local-003 | FAST_LOCAL_ROUTE | PASS | 706 | 0.71 | - | - | 65 | 454 | - | - | - | - | - |
| local-004 | FAST_LOCAL_ROUTE | PASS | 701 | 0.70 | - | - | 65 | 443 | - | - | - | - | - |
| local-005 | FAST_LOCAL_ROUTE | PASS | 738 | 0.74 | - | - | - | 478 | - | - | - | - | - |
| local-006 | FAST_LOCAL_ROUTE | PASS | 888 | 0.89 | - | - | - | 622 | - | - | - | - | - |
| local-007 | FAST_LOCAL_ROUTE | PASS | 798 | 0.80 | - | - | - | 546 | - | - | - | - | - |
| local-008 | FAST_LOCAL_ROUTE | PASS | 766 | 0.77 | - | - | 121 | 510 | - | - | - | - | - |
| server-exact-001 | FAST_SERVER_ROUTE | PASS | 776 | 0.78 | - | 15 | 109 | 516 | - | GENERAL | OPEN_ROUTE | /parts/a75d6544-2296-4c4c-a7cd-64596e66f6d7 | - |
| server-exact-002 | FAST_SERVER_ROUTE | PASS | 737 | 0.74 | - | 1 | 82 | 480 | - | GENERAL | OPEN_ROUTE | /parts/a2f9d478-b368-4c16-9048-4a064828c5c1 | - |
| server-exact-003 | FAST_SERVER_ROUTE | PASS | 739 | 0.74 | - | 20 | 58 | 485 | - | GENERAL | OPEN_ROUTE | /parts/09f39d2a-de00-4cbc-bd1a-241e9790111d | - |
| server-exact-004 | FAST_SERVER_ROUTE | PASS | 712 | 0.71 | - | 3 | 63 | 455 | - | GENERAL | OPEN_ROUTE | /parts/47c8753d-4544-4322-9aab-c175531eceeb | - |
| server-filter-001 | FAST_SERVER_ROUTE | PASS | 719 | 0.72 | - | 5 | 48 | 459 | - | GENERAL | OPEN_ROUTE | /self-quote?category=GPU&q=5090 | - |
| server-filter-002 | FAST_SERVER_ROUTE | PASS | 700 | 0.70 | - | 4 | 47 | 442 | - | GENERAL | OPEN_ROUTE | /self-quote?category=CPU&q=9950X3D | - |
| server-filter-003 | FAST_SERVER_ROUTE | PASS | 691 | 0.69 | - | 7 | 44 | 430 | - | GENERAL | OPEN_ROUTE | /self-quote?category=MOTHERBOARD&q=MSI+%EB%B3%B4%EB%93%9C | - |
| server-filter-004 | FAST_SERVER_ROUTE | PASS | 720 | 0.72 | - | 1 | 68 | 462 | - | GENERAL | OPEN_ROUTE | /self-quote?category=CASE&q=%EB%A6%AC%EC%95%88%EB%A6%AC+%EC%BC%80%EC%9D%B4%EC%8A%A4 | - |
| server-filter-005 | FAST_SERVER_ROUTE | PASS | 700 | 0.70 | - | 7 | 48 | 441 | - | GENERAL | OPEN_ROUTE | /self-quote?category=RAM&q=DDR5+%EB%9E%A8 | - |
| server-filter-006 | FAST_SERVER_ROUTE | PASS | 686 | 0.69 | - | 4 | 49 | 433 | - | GENERAL | OPEN_ROUTE | /self-quote?category=STORAGE&q=NVME+SSD | - |
| server-filter-007 | FAST_SERVER_ROUTE | PASS | 704 | 0.70 | - | 2 | 50 | 445 | - | GENERAL | OPEN_ROUTE | /self-quote?category=PSU&q=1000W+%ED%8C%8C%EC%9B%8C | - |
| server-filter-008 | FAST_SERVER_ROUTE | PASS | 714 | 0.71 | - | 7 | 57 | 455 | - | GENERAL | OPEN_ROUTE | /self-quote?category=COOLER&q=%EC%88%98%EB%9E%AD+%EC%BF%A8%EB%9F%AC | - |
| draft-001 | DRAFT_ACTION | PASS | 710 | 0.71 | - | 11 | 56 | - | 443 | PART | REMOVE_DRAFT_PART | - | - |
| draft-002 | DRAFT_ACTION | PASS | 721 | 0.72 | - | 8 | 58 | - | 453 | PART | UPDATE_DRAFT_QUANTITY | - | - |
| draft-003 | DRAFT_ACTION | PASS | 734 | 0.73 | - | 18 | 70 | - | - | PART | REPLACE_DRAFT_PART | - | - |
| draft-004 | DRAFT_ACTION | PASS | 731 | 0.73 | - | 16 | 66 | - | - | PART | REPLACE_DRAFT_PART | - | - |
| draft-005 | DRAFT_ACTION | PASS | 726 | 0.73 | - | 11 | 58 | - | - | PART | REPLACE_DRAFT_PART | - | - |
| draft-006 | DRAFT_ACTION | PASS | 707 | 0.71 | - | 10 | 48 | - | - | PART | REPLACE_DRAFT_PART | - | - |
| draft-007 | DRAFT_ACTION | PASS | 723 | 0.72 | - | 10 | 65 | - | - | PART | REPLACE_DRAFT_PART | - | - |
| draft-008 | DRAFT_ACTION | PASS | 707 | 0.71 | - | 10 | 63 | - | - | PART | REPLACE_DRAFT_PART | - | - |
| draft-009 | DRAFT_ACTION | PASS | 692 | 0.69 | - | 9 | 54 | - | - | PART | REPLACE_DRAFT_PART | - | - |
| draft-010 | DRAFT_ACTION | PASS | 709 | 0.71 | - | 9 | 47 | - | 440 | PART | UPDATE_DRAFT_QUANTITY | - | - |
| draft-011 | DRAFT_ACTION | PASS | 708 | 0.71 | - | 10 | 53 | - | - | PART | REPLACE_DRAFT_PART | - | - |
| draft-012 | DRAFT_ACTION | PASS | 708 | 0.71 | - | 10 | 59 | - | - | PART | REPLACE_DRAFT_PART | - | - |
| det-build-001 | DETERMINISTIC_RECOMMEND | PASS | 747 | 0.75 | - | 18 | 58 | - | - | BUDGET | - | - | - |
| det-build-002 | DETERMINISTIC_RECOMMEND | PASS | 696 | 0.70 | - | 5 | 48 | - | - | BUDGET | - | - | - |
| det-build-003 | DETERMINISTIC_RECOMMEND | PASS | 702 | 0.70 | - | 7 | 62 | - | - | BUDGET | - | - | - |
| det-build-004 | DETERMINISTIC_RECOMMEND | PASS | 694 | 0.69 | - | 8 | 45 | - | - | BUDGET | - | - | - |
| det-build-005 | DETERMINISTIC_RECOMMEND | PASS | 703 | 0.70 | - | 3 | 59 | - | - | BUDGET | - | - | - |
| det-build-006 | DETERMINISTIC_RECOMMEND | PASS | 703 | 0.70 | - | 4 | 51 | - | - | BUDGET | - | - | - |
| det-build-007 | DETERMINISTIC_RECOMMEND | PASS | 693 | 0.69 | - | 6 | 47 | - | - | BUDGET | - | - | - |
| det-build-008 | DETERMINISTIC_RECOMMEND | PASS | 703 | 0.70 | - | 3 | 50 | - | - | BUDGET | - | - | - |
| det-build-009 | DETERMINISTIC_RECOMMEND | PASS | 715 | 0.71 | - | 6 | 61 | - | - | BUDGET | - | - | - |
| det-build-010 | DETERMINISTIC_RECOMMEND | PASS | 744 | 0.74 | - | 19 | 58 | - | - | BUDGET | - | - | - |
| det-build-011 | DETERMINISTIC_RECOMMEND | PASS | 704 | 0.70 | - | 3 | 58 | - | - | BUDGET | - | - | - |
| det-build-012 | DETERMINISTIC_RECOMMEND | PASS | 688 | 0.69 | - | 5 | 46 | - | - | BUDGET | - | - | - |
| det-build-013 | DETERMINISTIC_RECOMMEND | PASS | 690 | 0.69 | - | 7 | 44 | - | - | BUDGET | - | - | - |
| det-part-001 | DETERMINISTIC_RECOMMEND | PASS | 819 | 0.82 | - | 5 | 48 | - | - | PART | ADD_PART_TO_DRAFT | - | - |
| det-part-002 | DETERMINISTIC_RECOMMEND | PASS | 712 | 0.71 | - | 3 | 60 | - | - | PART | ADD_PART_TO_DRAFT | - | - |
| det-part-003 | DETERMINISTIC_RECOMMEND | PASS | 696 | 0.70 | - | 4 | 45 | - | - | PART | ADD_PART_TO_DRAFT | - | - |
| llm-complex-001 | LLM_FULL_COMPLEX | PASS | 702 | 0.70 | - | 7 | 47 | - | - | BUDGET | - | - | - |
| llm-complex-002 | LLM_FULL_COMPLEX | PASS | 705 | 0.70 | - | 3 | 50 | - | - | BUDGET | - | - | - |
| llm-complex-003 | LLM_FULL_COMPLEX | PASS | 692 | 0.69 | - | 4 | 48 | - | - | BUDGET | - | - | - |
| llm-complex-004 | LLM_FULL_COMPLEX | PASS | 697 | 0.70 | - | 4 | 50 | - | - | BUDGET | - | - | - |
| llm-complex-005 | LLM_FULL_COMPLEX | PASS | 700 | 0.70 | - | 3 | 50 | - | - | BUDGET | - | - | - |
| llm-complex-006 | LLM_FULL_COMPLEX | PASS | 688 | 0.69 | - | 4 | 50 | - | - | BUDGET | - | - | - |
| llm-complex-007 | LLM_FULL_COMPLEX | PASS | 729 | 0.73 | - | 1 | 59 | - | - | BUDGET | - | - | - |
| llm-complex-008 | LLM_FULL_COMPLEX | PASS | 697 | 0.70 | - | 2 | 52 | - | - | BUDGET | - | - | - |
| llm-complex-009 | LLM_FULL_COMPLEX | PASS | 698 | 0.70 | - | 4 | 47 | - | - | BUDGET | - | - | - |
| llm-complex-010 | LLM_FULL_COMPLEX | PASS | 687 | 0.69 | - | 5 | 45 | - | - | BUDGET | - | - | - |
| llm-complex-011 | LLM_FULL_COMPLEX | PASS | 687 | 0.69 | - | 4 | 44 | - | - | BUDGET | - | - | - |
| llm-complex-012 | LLM_FULL_COMPLEX | PASS | 705 | 0.70 | - | 3 | 58 | - | - | BUDGET | - | - | - |

## 해석

- 이 리포트는 API 직접 호출 benchmark가 아니라 브라우저 입력, 자동 action, route 이동, draft 갱신까지 포함한 웹 체감 기준이다.
- FAST_LOCAL_ROUTE는 프론트 shortcut 품질을, FAST_SERVER_ROUTE는 서버 route resolver 품질을 본다.
- DRAFT_ACTION은 자동 장바구니 action이 실제 quote draft API와 화면 상태까지 마무리되는 시간을 본다.
- LLM_FULL_COMPLEX는 fast path가 처리하지 않는 복합 의도 요청이 5초 안에 끝나는지 확인한다.
