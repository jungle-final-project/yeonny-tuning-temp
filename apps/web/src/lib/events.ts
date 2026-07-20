import type { AiAssessmentContext } from '../features/quote/aiSelection';

export const AI_BUILD_ASSISTANT_OPEN_EVENT = 'buildgraph.aiAssistant.open';
export const AI_BUILD_ASSISTANT_TOGGLE_EVENT = 'buildgraph.aiAssistant.toggle';
export const AI_BUILD_ASSISTANT_CLOSE_EVENT = 'buildgraph.aiAssistant.close';
export const AI_BUILD_ASSISTANT_VISIBILITY_CHANGED_EVENT = 'buildgraph.aiAssistant.visibilityChanged';
export const SUPPORT_CHAT_OPEN_EVENT = 'buildgraph.supportChat.open';
export const SUPPORT_CHAT_CLOSE_EVENT = 'buildgraph.supportChat.close';
export const PERF_COMPARE_REQUEST_EVENT = 'buildgraph.perfCompare.request';

/**
 * 셀프견적 성능 패널의 "기존 조합 vs 변경 조합" 비교 대상.
 * FPS 비교는 CPU/GPU만 의미가 있다(벤치마크 근거가 있는 카테고리).
 */
export type PerfCompareTarget = {
  category: 'CPU' | 'GPU';
  partId: string;
  name: string;
  price: number;
};

export type AiAssistantOpenDetail = {
  prefill?: string;
  autoSubmit?: boolean;
  placement?: 'side' | 'center';
  assessmentContext?: AiAssessmentContext;
};

export type AiAssistantVisibilityDetail = {
  open: boolean;
};

let aiAssistantOpen = false;

export function isAiAssistantOpen() {
  return aiAssistantOpen;
}

export function setAiAssistantOpen(open: boolean) {
  if (aiAssistantOpen === open) return;
  aiAssistantOpen = open;
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<AiAssistantVisibilityDetail>(AI_BUILD_ASSISTANT_VISIBILITY_CHANGED_EVENT, {
    detail: { open }
  }));
}

export function openAiAssistant(detail?: AiAssistantOpenDetail) {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<AiAssistantOpenDetail>(AI_BUILD_ASSISTANT_OPEN_EVENT, { detail }));
}

export function closeAiAssistant() {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new Event(AI_BUILD_ASSISTANT_CLOSE_EVENT));
}

/** 성능 패널 교체 비교를 요청한다 — SelfQuotePage가 수신해 비교 모드를 켠다. */
export function requestPerfCompare(detail: PerfCompareTarget) {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<PerfCompareTarget>(PERF_COMPARE_REQUEST_EVENT, { detail }));
}

export function openSupportChat() {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new Event(SUPPORT_CHAT_OPEN_EVENT));
}

export function closeSupportChat() {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new Event(SUPPORT_CHAT_CLOSE_EVENT));
}
