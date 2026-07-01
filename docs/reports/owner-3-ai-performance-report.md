# 3번 AI 성능 및 고도화 검증 보고서

작성 기준: 2026-07-02 `prototype/main` 07dbe62 기반 3번 AI 영역

## 결론

AS AI Chat과 Build Chat의 성능 판단은 날짜별 benchmark 보고서를 기준으로 한다.

이번 문서는 구조와 검증 기준을 요약한다. 실제 profile별 수치의 source of truth는 `docs/reports/as-chat-profile-benchmark-YYYYMMDD.md`와 `docs/reports/build-chat-profile-benchmark-YYYYMMDD.md`다.

2026-07-01 전체 실험 기준으로는 `gpt-5.4-mini` 계열이 가장 좋은 기본값 후보로 나왔다. 다만 이번 변경에서는 기본 profile을 즉시 바꾸지 않고, 아래 실측 결과를 근거로 별도 설정 변경 PR에서 전환 여부를 결정한다.

## 2026-07-01 엄격 전체 실험 결과

실험 범위:

- AS Chat: 5개 profile x 6개 케이스 x vector-on/off = 60회
- Build Chat: 3개 profile x 5개 케이스 x vector-on/off = 30회
- 총 LLM 호출: 90회

보고서:

- AS Chat vector-on: `docs/reports/as-chat-profile-benchmark-20260701-vector-on.md`
- AS Chat vector-off: `docs/reports/as-chat-profile-benchmark-20260701-vector-off.md`
- Build Chat vector-on: `docs/reports/build-chat-profile-benchmark-20260701-vector-on.md`
- Build Chat vector-off: `docs/reports/build-chat-profile-benchmark-20260701-vector-off.md`

AS Chat 요약:

| variant | profile | successRate | avgFinalLatencyMs | p95FinalLatencyMs | schemaValidRate | groundedEvidenceRate | unsupportedClaims |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| vector-on | `AS_CHAT_FAST` | 66.7% | 10296 | 12900 | 100.0% | 100.0% | 0.2 |
| vector-on | `AS_CHAT_54_FAST` | 100.0% | 6900 | 7690 | 100.0% | 100.0% | 0.0 |
| vector-on | `AS_CHAT_54_MINI_FAST` | 100.0% | 5451 | 7199 | 100.0% | 100.0% | 0.0 |
| vector-on | `AS_CHAT_NANO_FAST` | 33.3% | 6714 | 10733 | 33.3% | 33.3% | 0.0 |
| vector-on | `AS_CHAT_BALANCED` | 100.0% | 12511 | 13851 | 100.0% | 100.0% | 0.0 |
| vector-off | `AS_CHAT_FAST` | 83.3% | 9295 | 10453 | 100.0% | 100.0% | 0.2 |
| vector-off | `AS_CHAT_54_FAST` | 83.3% | 6661 | 7136 | 100.0% | 83.3% | 0.2 |
| vector-off | `AS_CHAT_54_MINI_FAST` | 100.0% | 4580 | 5136 | 100.0% | 100.0% | 0.0 |
| vector-off | `AS_CHAT_NANO_FAST` | 50.0% | 6085 | 6893 | 50.0% | 50.0% | 0.0 |
| vector-off | `AS_CHAT_BALANCED` | 100.0% | 10766 | 11644 | 100.0% | 100.0% | 0.0 |

Build Chat 요약:

| variant | profile | successRate | avgLatencyMs | p95LatencyMs | schemaValidRate |
| --- | --- | ---: | ---: | ---: | ---: |
| vector-on | `BUILD_CHAT_FAST` | 100.0% | 4318 | 5204 | 100.0% |
| vector-on | `BUILD_CHAT_54_FAST` | 100.0% | 3833 | 4256 | 100.0% |
| vector-on | `BUILD_CHAT_54_MINI_FAST` | 100.0% | 3056 | 3484 | 100.0% |
| vector-off | `BUILD_CHAT_FAST` | 100.0% | 4160 | 5467 | 100.0% |
| vector-off | `BUILD_CHAT_54_FAST` | 100.0% | 3813 | 4585 | 100.0% |
| vector-off | `BUILD_CHAT_54_MINI_FAST` | 100.0% | 2669 | 3027 | 100.0% |

판정:

- `AS_CHAT_54_MINI_FAST`는 vector-on/off 모두 gate를 통과했고, 기존 `AS_CHAT_FAST`보다 성공률과 속도 모두 낫다.
- `AS_CHAT_NANO_FAST`는 JSON schema 실패가 반복되어 기본값 후보에서 제외한다.
- `AS_CHAT_BALANCED`는 품질은 안정적이지만 평균 지연 시간이 길어 기본 사용자 profile보다 고위험/관리자 검증 후보에 가깝다.
- Build Chat은 `BUILD_CHAT_54_MINI_FAST`가 모든 케이스를 통과하면서 가장 빠르다.
- 현재 고정 평가셋에서는 vector-on이 품질 이득을 만들지 못했고, 평균 latency는 더 길었다. 다만 `5090` RAG smoke에서 vector 검색 자체는 정상 작동하므로, 운영 기본값 변경은 더 큰 자연어 검색 평가셋으로 한 번 더 확인하는 것이 안전하다.

권장 후속 조치:

1. 별도 설정 변경 PR에서 기본 후보를 `AS_CHAT_54_MINI_FAST`, `BUILD_CHAT_54_MINI_FAST`로 전환 검토한다.
2. `RAG_VECTOR_ENABLED=false`를 기본값으로 바꿀지는 아직 보류한다. 현재 평가셋에서는 빠르지만, 의미 검색 품질을 볼 더 큰 견적/RAG 케이스가 필요하다.
3. nano profile은 기본 후보가 아니라 실패 사례 추적용 실험 profile로만 남긴다.

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

## Profile Benchmark 기준

최신 profile 비교는 아래 파일을 기준으로 본다.

- AS Chat: `docs/reports/as-chat-profile-benchmark-YYYYMMDD.md`
- Build Chat: `docs/reports/build-chat-profile-benchmark-YYYYMMDD.md`

AS Chat benchmark는 `AS_CHAT_FAST`, `AS_CHAT_54_FAST`, `AS_CHAT_54_MINI_FAST`, `AS_CHAT_NANO_FAST`, `AS_CHAT_BALANCED`를 같은 케이스로 비교한다. 보고서에는 `variant`, `firstEventMs`, `ragReadyMs`, `toolsReadyMs`, `llmOnlyMs`, `finalLatencyMs`, token, schema valid, grounded evidence, unsupported claim을 남긴다.

Build Chat benchmark는 `BUILD_CHAT_FAST`, `BUILD_CHAT_54_FAST`, `BUILD_CHAT_54_MINI_FAST`를 같은 케이스로 비교한다. “5090 포함”, “300만원 이하 5090”, “GPU 더 싼 걸로”, “RAM 64GB” 같은 최근 피드백 케이스를 포함한다.

기본값 전환 기준:

- schema valid 100%
- success rate 95% 이상
- grounded evidence 90% 이상
- unsupported claim 0
- p95 20초 이하
- 기존 기본 profile보다 평균 latency가 유의미하게 개선

위 기준을 통과하기 전까지 사용자 기본값은 `AS_CHAT_FAST`, `BUILD_CHAT_FAST`를 유지한다. `gpt-5.4`, `gpt-5.4-mini`, `gpt-5.4-nano`는 실험 후보이며 기본값 변경은 benchmark 결과로 판단한다.

RAG vector on/off 비교는 같은 profile과 같은 case를 두 번 실행하고 `tools/benchmark_as_chat_profiles.py --variant-label vector-on|vector-off`로 라벨을 분리한다.

## 책임 경계

이번 변경은 3번 AI 담당 범위에 맞춰 구현되었다.

- 3번 담당: AS Chat 답변 생성, RAG 근거 검색, Agent trace 저장
- 2번 담당: Tool 내부 판정 로직 고도화
- 4번 담당: AS 티켓 상태, AS 화면, 티켓 필드 반영
- 5번 담당: 인증/권한 완성

AS Chat은 `as_tickets.cause_candidates`나 `upgrade_candidates`를 직접 수정하지 않는다. 4번 담당이 필요할 때 AI 결과를 읽어 티켓에 반영하는 구조로 남겨두었다.
