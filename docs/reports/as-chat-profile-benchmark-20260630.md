# AS Chat AI Profile Benchmark

- generatedAt: 2026-06-30T06:29:16
- totalCases: 24

## Summary

| profile | provider | successRate | avgFirstEventMs | avgFinalLatencyMs | p95FinalLatencyMs | avgInputTokens | avgOutputTokens | avgTokens | schemaValidRate | avgGroundedEvidenceRate | avgUnsupportedClaims |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| AS_CHAT_FAST | openai | 100.0% | 17 | 9287 | 12838 | 1248 | 585 | 1833 | 100.0% | 100.0% | 0.0 |
| AS_CHAT_NANO_FAST | openai | 33.3% | 9 | 5643 | 6237 | 1239 | 676 | 1914 | 33.3% | 33.3% | 0.0 |
| AS_CHAT_BALANCED | openai | 100.0% | 16 | 11835 | 13784 | 1363 | 836 | 2199 | 100.0% | 100.0% | 0.0 |
| AS_CHAT_HIGH_QUALITY | openai | 83.3% | 17 | 16870 | 21170 | 1874 | 1127 | 3001 | 100.0% | 100.0% | 0.2 |

## Cases

| profile | provider | case | risk | ok | firstEventMs | finalLatencyMs | model | inTok | outTok | tokens | evidence | tools | actions | keywords | grounded | unsupported | failureType | error |
|---|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| AS_CHAT_FAST | openai | gpu-thermal-frame-drop | medium | yes | 28 | 11485 | gpt-5.5 | 1252 | 538 | 1790 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| AS_CHAT_FAST | openai | driver-crash-event-log | medium | yes | 13 | 8333 | gpt-5.5 | 1265 | 630 | 1895 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_FAST | openai | memory-pressure | low | yes | 27 | 12838 | gpt-5.5 | 1239 | 585 | 1824 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| AS_CHAT_FAST | openai | storage-bottleneck | low | yes | 4 | 7434 | gpt-5.5 | 1231 | 538 | 1769 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_FAST | openai | power-instability | high | yes | 5 | 7212 | gpt-5.5 | 1250 | 587 | 1837 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_FAST | openai | mixed-thermal-driver | high | yes | 25 | 8420 | gpt-5.5 | 1251 | 634 | 1885 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_NANO_FAST | openai | gpu-thermal-frame-drop | medium | no | - | 5803 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."', 'type': 'ResponseStatusException'} |
| AS_CHAT_NANO_FAST | openai | driver-crash-event-log | medium | yes | 14 | 5150 | gpt-5.4-nano | 1243 | 694 | 1937 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_NANO_FAST | openai | memory-pressure | low | no | - | 5601 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."', 'type': 'ResponseStatusException'} |
| AS_CHAT_NANO_FAST | openai | storage-bottleneck | low | yes | 4 | 5018 | gpt-5.4-nano | 1235 | 657 | 1892 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_NANO_FAST | openai | power-instability | high | no | - | 6048 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."', 'type': 'ResponseStatusException'} |
| AS_CHAT_NANO_FAST | openai | mixed-thermal-driver | high | no | - | 6237 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."', 'type': 'ResponseStatusException'} |
| AS_CHAT_BALANCED | openai | gpu-thermal-frame-drop | medium | yes | 4 | 11708 | gpt-5.5 | 1351 | 821 | 2172 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_BALANCED | openai | driver-crash-event-log | medium | yes | 4 | 11795 | gpt-5.5 | 1382 | 878 | 2260 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_BALANCED | openai | memory-pressure | low | yes | 26 | 10036 | gpt-5.5 | 1387 | 765 | 2152 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_BALANCED | openai | storage-bottleneck | low | yes | 24 | 11182 | gpt-5.5 | 1366 | 728 | 2094 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_BALANCED | openai | power-instability | high | yes | 25 | 13784 | gpt-5.5 | 1350 | 934 | 2284 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_BALANCED | openai | mixed-thermal-driver | high | yes | 16 | 12505 | gpt-5.5 | 1340 | 893 | 2233 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_HIGH_QUALITY | openai | gpu-thermal-frame-drop | medium | yes | 13 | 14198 | gpt-5.5 | 1857 | 1091 | 2948 | 5 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_HIGH_QUALITY | openai | driver-crash-event-log | medium | yes | 24 | 21170 | gpt-5.5 | 1874 | 1346 | 3220 | 5 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_HIGH_QUALITY | openai | memory-pressure | low | no | 3 | 18715 | gpt-5.5 | 1905 | 992 | 2897 | 5 | 3 | 3 | 3/3 | 100% | 1 | - |  |
| AS_CHAT_HIGH_QUALITY | openai | storage-bottleneck | low | yes | 15 | 11898 | gpt-5.5 | 1854 | 990 | 2844 | 5 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_HIGH_QUALITY | openai | power-instability | high | yes | 25 | 21168 | gpt-5.5 | 1889 | 1150 | 3039 | 5 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_HIGH_QUALITY | openai | mixed-thermal-driver | high | yes | 20 | 14070 | gpt-5.5 | 1864 | 1192 | 3056 | 5 | 3 | 3 | 3/3 | 100% | 0 | - |  |

## Selection Notes

- 기본 profile 후보는 schema valid 100%, 성공률 95% 이상을 먼저 만족해야 한다.
- 근거 없는 단정 카운트가 0인 profile을 우선한다.
- cause candidate가 RAG evidence 또는 Tool invocation을 참조하는 비율을 grounded evidence rate로 본다.
- 첫 진행 이벤트 평균이 1초 이하인 profile을 우선한다.
- 평균 응답 시간이 10초 이하인 profile을 우선한다.
- p95 응답 시간이 20초를 넘으면 사용자 체감상 감점한다.
- 품질 차이가 작으면 더 빠른 profile을 선택한다.
- benchmark 명령은 기본적으로 보고서 생성을 성공으로 본다. 전체 통과를 CI gate로 강제하려면 `--strict`를 사용한다.
