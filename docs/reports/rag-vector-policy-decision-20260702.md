# RAG Vector 경로별 정책 결정 보고서 - 2026-07-02

## 결론

현재 기본값 `RAG_VECTOR_ENABLED=true`는 유지한다. `vector-off` 계열은 평균 latency가 10~16ms 수준으로 빠르지만, `REQUIREMENT_PARSE`, `BUILD_RECOMMEND`, `PUBLIC_SEARCH`, `BUILD_EXPLAIN`에서 결과가 거의 비어 품질 기준으로 탈락이다.

경로별 override도 이번 단계에서는 기본값을 바꾸지 않는다. `RAG_VECTOR_REQUIREMENT_PARSE_ENABLED`, `RAG_VECTOR_BUILD_RECOMMEND_ENABLED`, `RAG_VECTOR_AS_ANALYZE_ENABLED`, `RAG_VECTOR_PUBLIC_SEARCH_ENABLED`는 전역값 상속을 유지한다.

## 근거

기준 파일: `docs/reports/rag-retrieval-benchmark-20260702.md`, `docs/reports/rag-retrieval-benchmark-20260702.json`

실행 전 `POST /api/admin/rag-embeddings/backfill`로 reusable RAG chunk 28개 전체에 `text-embedding-3-small` embedding을 백필했다. 백필 전에는 `embeddedTotal=0`이라 검색 결과가 비어 benchmark가 무효였다.

| variant | purpose | cases | topK hit | avg latency |
|---|---:|---:|---:|---:|
| vector-on | REQUIREMENT_PARSE | 90 | 71.1% | 311ms |
| vector-on | BUILD_RECOMMEND | 20 | 80.0% | 290ms |
| vector-on | AS_ANALYZE | 50 | 92.0% | 347ms |
| vector-on | BUILD_EXPLAIN | 10 | 100.0% | 371ms |
| vector-on | PUBLIC_SEARCH | 20 | 90.0% | 299ms |
| vector-off | REQUIREMENT_PARSE | 90 | 0.0% | 13ms |
| vector-off | BUILD_RECOMMEND | 20 | 0.0% | 11ms |
| vector-off | AS_ANALYZE | 50 | 2.0% | 13ms |
| vector-off | BUILD_EXPLAIN | 10 | 0.0% | 14ms |
| vector-off | PUBLIC_SEARCH | 20 | 0.0% | 11ms |

## 운영 정책

- public `/api/rag/search`는 관리자/검증 검색 품질이 중요하므로 vector 유지.
- `AS_ANALYZE`는 topK 92%로 현재 기준 통과이므로 vector 유지.
- `BUILD_EXPLAIN`은 topK 100%로 vector 유지.
- `REQUIREMENT_PARSE`와 `BUILD_RECOMMEND`는 vector 유지가 맞지만 hit rate 목표에는 부족하다. 다음 개선 대상은 vector on/off가 아니라 seed/rerank 품질이다.

## 다음 개선 기준

- `REQUIREMENT_PARSE` topK hit 85% 이상.
- `BUILD_RECOMMEND` topK hit 90% 이상.
- `AS_ANALYZE`, `BUILD_EXPLAIN`, `PUBLIC_SEARCH`는 현재 수준보다 하락하지 않을 것.
- latency는 Build/AS Chat 전체 체감에서 LLM 호출 시간이 지배적이므로, retrieval 260~360ms는 현재 허용 범위다.

## 재실험 명령

```powershell
python tools\benchmark_rag_retrieval.py --variant-label vector-on
```

경로별 override를 비교할 때는 API 환경변수를 바꾸고 같은 명령을 다른 label로 실행한다.

```powershell
$env:RAG_VECTOR_REQUIREMENT_PARSE_ENABLED="false"
docker compose up --build -d api
python tools\benchmark_rag_retrieval.py --variant-label requirement-vector-off
```
