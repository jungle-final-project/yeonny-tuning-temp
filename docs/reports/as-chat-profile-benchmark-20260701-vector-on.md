# AS Chat AI Profile Benchmark

- generatedAt: 2026-07-01T20:00:37
- totalCases: 30

## Summary

| variant | profile | provider | successRate | avgFirstEventMs | avgRagReadyMs | avgToolsReadyMs | avgLlmOnlyMs | avgFinalLatencyMs | p95FinalLatencyMs | avgInputTokens | avgOutputTokens | avgTokens | schemaValidRate | avgGroundedEvidenceRate | avgUnsupportedClaims |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| vector-on | AS_CHAT_FAST | openai | 66.7% | 18 | 266 | 273 | 9133 | 10296 | 12900 | 1243 | 618 | 1861 | 100.0% | 100.0% | 0.2 |
| vector-on | AS_CHAT_54_FAST | openai | 100.0% | 19 | 428 | 433 | 5858 | 6900 | 7690 | 1248 | 678 | 1925 | 100.0% | 100.0% | 0.0 |
| vector-on | AS_CHAT_54_MINI_FAST | openai | 100.0% | 16 | 312 | 318 | 4651 | 5451 | 7199 | 1245 | 645 | 1889 | 100.0% | 100.0% | 0.0 |
| vector-on | AS_CHAT_NANO_FAST | openai | 33.3% | 16 | 447 | 452 | 4580 | 6714 | 10733 | 1241 | 654 | 1896 | 33.3% | 33.3% | 0.0 |
| vector-on | AS_CHAT_BALANCED | openai | 100.0% | 9 | 267 | 272 | 11174 | 12511 | 13851 | 1360 | 834 | 2194 | 100.0% | 100.0% | 0.0 |

## Cases

| variant | profile | provider | case | risk | ok | firstEventMs | ragReadyMs | toolsReadyMs | llmOnlyMs | finalLatencyMs | model | inTok | outTok | tokens | evidence | tools | actions | keywords | grounded | unsupported | failureType | error |
|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| vector-on | AS_CHAT_FAST | openai | gpu-thermal-frame-drop | medium | no | 19 | 273 | 284 | 11255 | 12629 | gpt-5.5 | 1238 | 601 | 1839 | 2 | 3 | 2 | 1/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_FAST | openai | driver-crash-event-log | medium | yes | 5 | 255 | 262 | 8809 | 9913 | gpt-5.5 | 1251 | 654 | 1905 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_FAST | openai | memory-pressure | low | no | 20 | 259 | 265 | 8641 | 9751 | gpt-5.5 | 1270 | 539 | 1809 | 2 | 3 | 2 | 3/3 | 100% | 1 | - |  |
| vector-on | AS_CHAT_FAST | openai | storage-bottleneck | low | yes | 29 | 264 | 272 | 7479 | 8497 | gpt-5.5 | 1239 | 585 | 1824 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_FAST | openai | power-instability | high | yes | 19 | 292 | 298 | 11490 | 12900 | gpt-5.5 | 1237 | 719 | 1956 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_FAST | openai | mixed-thermal-driver | high | yes | 17 | 250 | 257 | 7125 | 8088 | gpt-5.5 | 1223 | 611 | 1834 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_54_FAST | openai | gpu-thermal-frame-drop | medium | yes | 5 | 230 | 236 | 6535 | 7412 | gpt-5.4 | 1224 | 735 | 1959 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_54_FAST | openai | driver-crash-event-log | medium | yes | 21 | 289 | 294 | 5327 | 6173 | gpt-5.4 | 1252 | 680 | 1932 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_54_FAST | openai | memory-pressure | low | yes | 22 | 270 | 275 | 5505 | 6339 | gpt-5.4 | 1285 | 650 | 1935 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_54_FAST | openai | storage-bottleneck | low | yes | 39 | 1205 | 1210 | 5234 | 7082 | gpt-5.4 | 1224 | 571 | 1795 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_54_FAST | openai | power-instability | high | yes | 23 | 278 | 283 | 6733 | 7690 | gpt-5.4 | 1247 | 738 | 1985 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_54_FAST | openai | mixed-thermal-driver | high | yes | 5 | 296 | 301 | 5817 | 6702 | gpt-5.4 | 1253 | 692 | 1945 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_54_MINI_FAST | openai | gpu-thermal-frame-drop | medium | yes | 30 | 310 | 316 | 6247 | 7199 | gpt-5.4-mini | 1252 | 700 | 1952 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_54_MINI_FAST | openai | driver-crash-event-log | medium | yes | 21 | 144 | 157 | 4938 | 5591 | gpt-5.4-mini | 1235 | 635 | 1870 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_54_MINI_FAST | openai | memory-pressure | low | yes | 4 | 565 | 570 | 3987 | 4987 | gpt-5.4-mini | 1262 | 550 | 1812 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_54_MINI_FAST | openai | storage-bottleneck | low | yes | 30 | 318 | 323 | 4209 | 4985 | gpt-5.4-mini | 1264 | 597 | 1861 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_54_MINI_FAST | openai | power-instability | high | yes | 5 | 288 | 293 | 4099 | 4815 | gpt-5.4-mini | 1228 | 707 | 1935 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_54_MINI_FAST | openai | mixed-thermal-driver | high | yes | 4 | 247 | 252 | 4427 | 5128 | gpt-5.4-mini | 1227 | 679 | 1906 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_NANO_FAST | openai | gpu-thermal-frame-drop | medium | yes | 28 | 607 | 612 | 5016 | 6188 | gpt-5.4-nano | 1252 | 698 | 1950 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_NANO_FAST | openai | driver-crash-event-log | medium | no | - | - | - | - | 10733 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'type': 'ResponseStatusException', 'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."'} |
| vector-on | AS_CHAT_NANO_FAST | openai | memory-pressure | low | no | - | - | - | - | 5579 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'type': 'ResponseStatusException', 'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."'} |
| vector-on | AS_CHAT_NANO_FAST | openai | storage-bottleneck | low | yes | 4 | 287 | 293 | 4145 | 4856 | gpt-5.4-nano | 1230 | 611 | 1841 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_NANO_FAST | openai | power-instability | high | no | - | - | - | - | 7265 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'type': 'ResponseStatusException', 'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."'} |
| vector-on | AS_CHAT_NANO_FAST | openai | mixed-thermal-driver | high | no | - | - | - | - | 5666 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'type': 'ResponseStatusException', 'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."'} |
| vector-on | AS_CHAT_BALANCED | openai | gpu-thermal-frame-drop | medium | yes | 4 | 296 | 301 | 12374 | 13851 | gpt-5.5 | 1369 | 867 | 2236 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_BALANCED | openai | driver-crash-event-log | medium | yes | 24 | 279 | 285 | 9629 | 10849 | gpt-5.5 | 1357 | 834 | 2191 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_BALANCED | openai | memory-pressure | low | yes | 3 | 249 | 254 | 9949 | 11152 | gpt-5.5 | 1371 | 806 | 2177 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_BALANCED | openai | storage-bottleneck | low | yes | 4 | 294 | 299 | 10880 | 12216 | gpt-5.5 | 1331 | 688 | 2019 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_BALANCED | openai | power-instability | high | yes | 4 | 234 | 239 | 12432 | 13846 | gpt-5.5 | 1351 | 857 | 2208 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| vector-on | AS_CHAT_BALANCED | openai | mixed-thermal-driver | high | yes | 14 | 248 | 252 | 11781 | 13153 | gpt-5.5 | 1380 | 953 | 2333 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |

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
