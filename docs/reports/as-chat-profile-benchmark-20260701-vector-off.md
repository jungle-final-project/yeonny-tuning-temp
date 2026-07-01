# AS Chat AI Profile Benchmark

- generatedAt: 2026-07-01T19:54:20
- totalCases: 30

## Summary

| variant | profile | provider | successRate | avgFirstEventMs | avgRagReadyMs | avgToolsReadyMs | avgLlmOnlyMs | avgFinalLatencyMs | p95FinalLatencyMs | avgInputTokens | avgOutputTokens | avgTokens | schemaValidRate | avgGroundedEvidenceRate | avgUnsupportedClaims |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| vector-off | AS_CHAT_FAST | openai | 83.3% | 13 | 19 | 26 | 8453 | 9295 | 10453 | 1241 | 598 | 1839 | 100.0% | 100.0% | 0.2 |
| vector-off | AS_CHAT_54_FAST | openai | 83.3% | 18 | 11 | 16 | 6061 | 6661 | 7136 | 1245 | 694 | 1939 | 100.0% | 83.3% | 0.2 |
| vector-off | AS_CHAT_54_MINI_FAST | openai | 100.0% | 12 | 10 | 15 | 4158 | 4580 | 5136 | 1254 | 647 | 1900 | 100.0% | 100.0% | 0.0 |
| vector-off | AS_CHAT_NANO_FAST | openai | 50.0% | 18 | 10 | 14 | 5251 | 6085 | 6893 | 1238 | 678 | 1915 | 50.0% | 50.0% | 0.0 |
| vector-off | AS_CHAT_BALANCED | openai | 100.0% | 10 | 10 | 15 | 9831 | 10766 | 11644 | 1339 | 820 | 2160 | 100.0% | 100.0% | 0.0 |

## Cases

| variant | profile | provider | case | risk | ok | firstEventMs | ragReadyMs | toolsReadyMs | llmOnlyMs | finalLatencyMs | model | inTok | outTok | tokens | evidence | tools | actions | keywords | grounded | unsupported | failureType | error |
|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| vector-off | AS_CHAT_FAST | openai | gpu-thermal-frame-drop | medium | yes | 20 | 36 | 46 | 9508 | 10453 | gpt-5.5 | 1259 | 579 | 1838 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_FAST | openai | driver-crash-event-log | medium | yes | 6 | 16 | 23 | 7774 | 8562 | gpt-5.5 | 1239 | 611 | 1850 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_FAST | openai | memory-pressure | low | no | 17 | 18 | 26 | 8418 | 9283 | gpt-5.5 | 1265 | 571 | 1836 | 2 | 3 | 2 | 3/3 | 100% | 1 | - |  |
| vector-off | AS_CHAT_FAST | openai | storage-bottleneck | low | yes | 4 | 14 | 21 | 6960 | 7632 | gpt-5.5 | 1206 | 562 | 1768 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_FAST | openai | power-instability | high | yes | 28 | 14 | 20 | 8903 | 9802 | gpt-5.5 | 1248 | 622 | 1870 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_FAST | openai | mixed-thermal-driver | high | yes | 5 | 14 | 20 | 9154 | 10040 | gpt-5.5 | 1227 | 645 | 1872 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_54_FAST | openai | gpu-thermal-frame-drop | medium | yes | 28 | 12 | 18 | 6390 | 7030 | gpt-5.4 | 1237 | 769 | 2006 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_54_FAST | openai | driver-crash-event-log | medium | yes | 28 | 12 | 18 | 5574 | 6147 | gpt-5.4 | 1232 | 710 | 1942 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_54_FAST | openai | memory-pressure | low | no | 22 | 12 | 17 | 5740 | 6313 | gpt-5.4 | 1283 | 612 | 1895 | 2 | 3 | 2 | 3/3 | 0% | 1 | - |  |
| vector-off | AS_CHAT_54_FAST | openai | storage-bottleneck | low | yes | 23 | 10 | 15 | 5817 | 6395 | gpt-5.4 | 1239 | 597 | 1836 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_54_FAST | openai | power-instability | high | yes | 4 | 10 | 15 | 6331 | 6945 | gpt-5.4 | 1229 | 687 | 1916 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_54_FAST | openai | mixed-thermal-driver | high | yes | 4 | 10 | 15 | 6512 | 7136 | gpt-5.4 | 1249 | 790 | 2039 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_54_MINI_FAST | openai | gpu-thermal-frame-drop | medium | yes | 22 | 10 | 15 | 4369 | 4822 | gpt-5.4-mini | 1253 | 652 | 1905 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_54_MINI_FAST | openai | driver-crash-event-log | medium | yes | 5 | 10 | 15 | 3961 | 4353 | gpt-5.4-mini | 1243 | 655 | 1898 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_54_MINI_FAST | openai | memory-pressure | low | yes | 5 | 11 | 16 | 4155 | 4575 | gpt-5.4-mini | 1276 | 607 | 1883 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_54_MINI_FAST | openai | storage-bottleneck | low | yes | 30 | 10 | 14 | 3643 | 4037 | gpt-5.4-mini | 1232 | 534 | 1766 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_54_MINI_FAST | openai | power-instability | high | yes | 5 | 9 | 14 | 4678 | 5136 | gpt-5.4-mini | 1243 | 742 | 1985 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_54_MINI_FAST | openai | mixed-thermal-driver | high | yes | 4 | 11 | 16 | 4143 | 4559 | gpt-5.4-mini | 1274 | 692 | 1966 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_NANO_FAST | openai | gpu-thermal-frame-drop | medium | yes | 25 | 10 | 14 | 5403 | 5952 | gpt-5.4-nano | 1239 | 695 | 1934 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_NANO_FAST | openai | driver-crash-event-log | medium | no | - | - | - | - | 6893 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."', 'type': 'ResponseStatusException'} |
| vector-off | AS_CHAT_NANO_FAST | openai | memory-pressure | low | no | - | - | - | - | 6359 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."', 'type': 'ResponseStatusException'} |
| vector-off | AS_CHAT_NANO_FAST | openai | storage-bottleneck | low | yes | 14 | 10 | 15 | 4305 | 4737 | gpt-5.4-nano | 1240 | 672 | 1912 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_NANO_FAST | openai | power-instability | high | yes | 14 | 9 | 14 | 6044 | 6638 | gpt-5.4-nano | 1234 | 666 | 1900 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_NANO_FAST | openai | mixed-thermal-driver | high | no | - | - | - | - | 5932 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."', 'type': 'ResponseStatusException'} |
| vector-off | AS_CHAT_BALANCED | openai | gpu-thermal-frame-drop | medium | yes | 4 | 11 | 16 | 10642 | 11644 | gpt-5.5 | 1331 | 806 | 2137 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_BALANCED | openai | driver-crash-event-log | medium | yes | 23 | 10 | 15 | 9794 | 10734 | gpt-5.5 | 1322 | 811 | 2133 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_BALANCED | openai | memory-pressure | low | yes | 4 | 11 | 15 | 9584 | 10494 | gpt-5.5 | 1349 | 776 | 2125 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_BALANCED | openai | storage-bottleneck | low | yes | 24 | 10 | 15 | 9445 | 10355 | gpt-5.5 | 1370 | 752 | 2122 | 3 | 3 | 3 | 2/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_BALANCED | openai | power-instability | high | yes | 4 | 10 | 15 | 9428 | 10323 | gpt-5.5 | 1321 | 902 | 2223 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| vector-off | AS_CHAT_BALANCED | openai | mixed-thermal-driver | high | yes | 3 | 10 | 15 | 10092 | 11047 | gpt-5.5 | 1342 | 876 | 2218 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |

## Selection Notes

- 기본 profile 후보는 schema valid 100%, 성공률 95% 이상을 먼저 만족해야 한다.
- 근거 없는 단정 카운트가 0인 profile을 우선한다.
- cause candidate가 RAG evidence 또는 Tool invocation을 참조하는 비율을 grounded evidence rate로 본다.
- 첫 진행 이벤트 평균이 1초 이하인 profile을 우선한다.
- 평균 응답 시간이 10초 이하인 profile을 우선한다.
- p95 응답 시간이 20초를 넘으면 사용자 체감상 감점한다.
- 품질 차이가 작으면 더 빠른 profile을 선택한다.
- RAG vector on/off 비교는 같은 profile과 case를 두 번 실행하고 `--variant-label vector-on|vector-off`로 구분한다.
- benchmark 명령은 기본적으로 보고서 생성을 성공으로 본다. 전체 통과를 CI gate로 강제하려면 `--strict`를 사용한다.
