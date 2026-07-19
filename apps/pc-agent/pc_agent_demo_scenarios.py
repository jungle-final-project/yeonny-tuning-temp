from __future__ import annotations


LIVE_DATA_MODE = "LIVE"
DEMO_DATA_MODE = "DEMO"

GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID = "GRAPHICS_CODE43_REMOTE_SUPPORT"
GRAPHICS_CODE43_REMOTE_SUPPORT_SYMPTOM = (
    "게임 중 화면이 잠깐 꺼졌다가 다시 켜지고,\n"
    "이후 게임이 심하게 느려졌어요."
)


def normalize_demo_symptom(symptom: str) -> str:
    return " ".join(str(symptom or "").strip().split())


def demo_scenario_id(mode: str, symptom: str) -> str | None:
    if str(mode or "").strip().upper() != DEMO_DATA_MODE:
        return None
    if normalize_demo_symptom(symptom) != normalize_demo_symptom(GRAPHICS_CODE43_REMOTE_SUPPORT_SYMPTOM):
        return None
    return GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID
