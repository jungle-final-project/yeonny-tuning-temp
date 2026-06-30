# 3번 AI 성능 및 고도화 검증 보고서

작성 기준: 2026-06-30 로컬 `hyunjin` 저장소 기준

## 결론

AS AI Chat 고도화 변경은 로컬 빌드, 프론트 테스트, Docker 재빌드, 실제 OpenAI 호출까지 통과했다.

이번 변경의 핵심은 AS Chat 답변을 단순 문자열 생성에서 Structured Outputs 기반 JSON 생성으로 바꾼 것이다. 이로 인해 프론트와 관리자 화면이 기대하는 `causeCandidates`, `nextActions`, `escalation`, `ticketDraft` 구조를 더 안정적으로 받을 수 있다.

## 검증 결과

| 항목 | 결과 |
| --- | --- |
| OpenAPI 검증 | 통과, 48 paths |
| 백엔드 테스트 | 통과 |
| 프론트 빌드 | 통과 |
| 프론트 Playwright 테스트 | 통과, 51 passed |
| Docker Compose 설정 검증 | 통과 |
| Docker no-cache 재빌드 | 통과 |
| API health | `UP`, database `UP` |
| 웹 `/support/ai-chat` | HTTP 200 |
| 실제 AS AI Chat POST | 성공 |

실제 AS AI Chat 호출 결과:

| 응답 항목 | 결과 |
| --- | --- |
| assistant message | 생성 성공 |
| 원인 후보 | 4개 |
| 다음 조치 | 6개 |
| RAG 근거 | 4개 |
| Tool 결과 | 3개 |
| DB 메시지 저장 | USER/ASSISTANT 2개 저장 확인 |
| API 로그 | ERROR/WARN/Exception 없음 |

## 고도화 내용

### Structured Outputs 적용

`OpenAiResponsesClient`는 OpenAI Responses API 호출 시 `text.format.type = json_schema`와 `strict = true`를 사용한다.

기대 효과:

- LLM이 자유 형식 문장을 반환해 JSON 파싱이 실패하는 문제 감소
- 프론트와 관리자 화면이 같은 응답 구조를 안정적으로 사용
- AS 답변의 원인 후보, 조치, 에스컬레이션 여부를 UI에서 바로 분리 가능

### 모델 설정

현재 기본 설정은 다음과 같다.

```env
OPENAI_MODEL=gpt-5.5
OPENAI_REASONING_EFFORT=medium
```

품질 우선 설정이므로 AS 분석 답변 품질에는 유리하다. 대신 응답 시간과 비용은 nano profile보다 높다. 데모 속도가 더 중요해지면 `AS_CHAT_NANO_FAST`(`gpt-5.4-nano`)를 같은 benchmark 구조에서 비교한 뒤 기본 profile 전환 여부를 판단한다.

### RAG 검색 개선

`AgentRagRetrievalService`는 AS 증상 표현을 경량 확장한다.

확장 대상:

- GPU 온도, 발열, 쓰로틀링
- 프레임 드랍, 끊김, 렉
- 드라이버 오류, 크래시
- RAM 부족, 메모리 압박
- 저장장치 병목, 로딩 지연
- 전원 불안정, PSU 여유 부족

이 방식은 embedding 없이도 한국어 AS 증상에서 관련 근거를 더 잘 찾기 위한 경량 hybrid RAG 전략이다.

## 성능 판단

현재 AS Chat은 사용자 메시지 1회당 LLM 호출 1회 구조다.

장점:

- Tool 호출 루프를 LLM 안에 넣지 않아 지연 시간과 비용이 폭증하지 않음
- 2번 담당자의 Tool 구현 영역을 침범하지 않음
- Tool 결과와 RAG 근거를 서버에서 모은 뒤 LLM에는 요약과 구조화만 맡김
- DB에는 agent session, tool invocation, rag evidence trace가 남음

주의점:

- `gpt-5.5`는 품질은 높지만 응답 시간이 길 수 있음
- Structured Outputs는 schema 검증 안정성을 얻는 대신 요청 payload가 약간 커짐
- 현재 RAG는 keyword-hybrid 방식이라 문서가 크게 늘면 검색 품질 한계가 생길 수 있음

## 한계와 다음 개선 방향

현재 구조는 데모와 Sprint 개발 기준으로는 충분하지만, 운영 수준까지 가려면 다음 개선이 필요하다.

1. AS 평가셋 추가
   - 온도, 프레임 드랍, 드라이버 오류, RAM 부족, 저장장치 병목, 전원 문제 케이스를 고정 평가셋으로 둔다.

2. RAG 검색 품질 계량화
   - 기대 근거가 top 3 안에 들어오는지 자동 테스트한다.

3. pgvector rerank 도입 검토
   - RAG 문서와 로그 근거가 수천 chunk 이상으로 늘면 keyword 검색만으로는 부족하다.

4. 모델 라우팅
   - 단순 질문은 경량 모델, 복합 AS 분석은 고성능 모델을 쓰는 구조로 비용과 지연을 조절한다.

5. 응답 지연 측정
   - AS Chat POST 응답 시간을 DB 또는 로그에 남겨 모델별 실제 비용 대비 품질을 비교한다.

## Profile Benchmark 결과

2026-06-30에 `tools/benchmark_as_chat_profiles.py`로 AS Chat profile을 실제 OpenAI 호출 기준으로 비교했다. 이 결과는 `POST /api/ai/as-chat/stream`, compact prompt, profile별 출력 제한 적용 후 측정한 값이다. 최신 세부 결과는 `docs/reports/as-chat-profile-benchmark-20260630.md`를 기준으로 본다.

| profile | successRate | avgFirstEventMs | avgFinalLatencyMs | p95FinalLatencyMs | avgTokens | schemaValidRate | avgUnsupportedClaims |
|---|---:|---:|---:|---:|---:|---:|---:|
| `AS_CHAT_FAST` | 100.0% | 17 | 9287 | 12838 | 1833 | 100.0% | 0.0 |
| `AS_CHAT_NANO_FAST` | 33.3% | 9 | 5643 | 6237 | 1914 | 33.3% | 0.0 |
| `AS_CHAT_BALANCED` | 100.0% | 16 | 11835 | 13784 | 2199 | 100.0% | 0.0 |
| `AS_CHAT_HIGH_QUALITY` | 83.3% | 17 | 16870 | 21170 | 3001 | 100.0% | 0.2 |

판단:

- `AS_CHAT_FAST`와 `AS_CHAT_BALANCED`는 구조화 응답과 자동 품질 기준을 통과했다.
- `AS_CHAT_NANO_FAST`는 평균 5.6초대로 가장 빠르지만 schema valid 33.3%라 기본값 전환 조건을 만족하지 못했다. nano는 실험 후보로 유지한다.
- `AS_CHAT_FAST`가 평균 10초 이하와 p95 20초 이하 조건을 만족하므로 현재 사용자 기본 profile로 둔다.
- 첫 진행 이벤트는 모든 profile이 평균 1초 이내라, 사용자는 요청 직후 처리 진행 상태를 볼 수 있다.
- `AS_CHAT_BALANCED`와 `AS_CHAT_HIGH_QUALITY`는 관리자 검증 또는 고위험 분석 후보로 유지한다.
- 현재 PR 범위의 AS Chat profile 비교는 OpenAI profile만 대상으로 한다. 다른 provider 비교는 별도 합의 후 추가한다.

## 책임 경계

이번 변경은 3번 AI 담당 범위에 맞춰 구현되었다.

- 3번 담당: AS Chat 답변 생성, RAG 근거 검색, Agent trace 저장
- 2번 담당: Tool 내부 판정 로직 고도화
- 4번 담당: AS 티켓 상태, AS 화면, 티켓 필드 반영
- 5번 담당: 인증/권한 완성

AS Chat은 `as_tickets.cause_candidates`나 `upgrade_candidates`를 직접 수정하지 않는다. 4번 담당이 필요할 때 AI 결과를 읽어 티켓에 반영하는 구조로 남겨두었다.
