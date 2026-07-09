export const AI_BUILD_ASSISTANT_OPEN_EVENT = 'buildgraph.aiAssistant.open';
export const AI_BUILD_ASSISTANT_TOGGLE_EVENT = 'buildgraph.aiAssistant.toggle';
export const AI_BUILD_ASSISTANT_CLOSE_EVENT = 'buildgraph.aiAssistant.close';
export const AI_BUILD_ASSISTANT_VISIBILITY_CHANGED_EVENT = 'buildgraph.aiAssistant.visibilityChanged';
export const SUPPORT_CHAT_OPEN_EVENT = 'buildgraph.supportChat.open';
export const SUPPORT_CHAT_CLOSE_EVENT = 'buildgraph.supportChat.close';

export type AiAssistantOpenDetail = {
  prefill?: string;
  autoSubmit?: boolean;
  placement?: 'side' | 'center';
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

export function openSupportChat() {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new Event(SUPPORT_CHAT_OPEN_EVENT));
}

export function closeSupportChat() {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new Event(SUPPORT_CHAT_CLOSE_EVENT));
}
