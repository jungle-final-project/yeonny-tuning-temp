#!/usr/bin/env python3
"""데모 직전 실행용 Build Chat 캐시 워밍 스크립트.

데모에서 쓸 대표 프롬프트를 미리 호출해 Redis exact + pgvector semantic 캐시에 올려둔다.
특히 LLM 경로(예산 없는 용도 추천 등)를 미리 채워 데모 중 5초 LLM 대기를 없앤다.

주의: Build Chat 캐시 TTL은 기본 600초, prewarm은 3600초다. 워밍 효과가 유지되는
동안 데모를 진행해야 하므로 **데모 시작 직전에 실행**한다.

사용:
  python tools/warm_build_chat_demo_cache.py --user-email user@example.com --user-password 'passw0rd!'
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request


# 데모에서 실제로 시연할 대표 프롬프트. 예산 추천은 티어/그리디, 용도-only는 LLM 경로를 태워 캐시에 올린다.
DEMO_PROMPTS = [
    "200만원 게이밍 PC 추천해줘",
    "300만원대 게임용 PC 추천해줘",
    "500만원 AI CUDA 학습용 워크스테이션 추천해줘",
    "800만원으로 최고급 PC 추천해줘",
    "1300만원 게이밍 PC 추천해줘",
    "영상편집용 PC 추천해줘",
    "발로란트 240Hz용 컴퓨터 맞춰줘",
    "로스트아크 레이드 돌릴 PC 추천",
    "개발용으로 도커 여러개 띄울 워크스테이션 추천",
    "저소음 사무용 PC 추천해줘",
]


def main() -> int:
    parser = argparse.ArgumentParser(description="Warm Build Chat demo cache")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--user-email", default="user@example.com")
    parser.add_argument("--user-password", default="passw0rd!")
    parser.add_argument("--rounds", type=int, default=2, help="각 프롬프트 반복 횟수(2회: cold+warm 확인)")
    args = parser.parse_args()

    token = login(args.base_url, args.user_email, args.user_password)
    print(f"{'prompt':44} {'round1(ms)':>11} {'round2(ms)':>11} {'answerType':>11} {'builds':>7}")
    print("-" * 90)

    warmed = 0
    for prompt in DEMO_PROMPTS:
        latencies = []
        response = None
        for _ in range(max(1, args.rounds)):
            started = time.perf_counter()
            response = call(args.base_url, token, prompt)
            latencies.append(round((time.perf_counter() - started) * 1000))
        answer = response.get("answerType") if response else "-"
        builds = len(response.get("builds") or []) if response else 0
        r1 = latencies[0]
        r2 = latencies[1] if len(latencies) > 1 else latencies[0]
        print(f"{prompt[:44]:44} {r1:>11} {r2:>11} {str(answer):>11} {builds:>7}")
        if response is not None:
            warmed += 1

    print("-" * 90)
    print(f"워밍 완료: {warmed}/{len(DEMO_PROMPTS)} 프롬프트. round2가 round1보다 크게 낮으면 캐시 적중.")
    print("※ Build Chat 캐시 TTL(기본 600초)이 지나면 재워밍이 필요하니 데모 직전에 실행하세요.")
    return 0 if warmed == len(DEMO_PROMPTS) else 1


def call(base_url: str, token: str, prompt: str) -> dict | None:
    try:
        return request_json(base_url, "POST", "/api/ai/build-chat", token, {"message": prompt}, timeout=180)
    except RuntimeError as exc:
        print(f"  [warn] '{prompt}' 호출 실패: {exc}")
        return None


def login(base_url: str, email: str, password: str) -> str:
    response = request_json(base_url, "POST", "/api/auth/login", None, {"email": email, "password": password})
    token = response.get("accessToken")
    if not token:
        raise RuntimeError(f"login failed for {email}: accessToken missing")
    return token


def request_json(base_url, method, path, token, body, timeout=60):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(f"{base_url}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path} failed: HTTP {exc.code} {raw}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"{method} {path} failed: {exc}") from exc


if __name__ == "__main__":
    raise SystemExit(main())
