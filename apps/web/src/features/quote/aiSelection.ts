import type { QuoteDraft } from '../parts/types';
import { getCachedAuthUser } from '../../lib/api';

export const AI_SELECTED_BUILD_STORAGE_KEY = 'buildgraph.ai.selectedBuild';
export const AI_SELECTED_BUILD_CHANGED_EVENT = 'buildgraph.ai.selectedBuildChanged';
export const AI_ASSISTANT_SESSION_STORAGE_KEY = 'buildgraph.ai.assistantSession';
export const AI_ASSISTANT_SESSION_CHANGED_EVENT = 'buildgraph.ai.assistantSessionChanged';

export type AiBuildTier = 'budget' | 'balanced' | 'performance';
export type PartCategory = 'CPU' | 'MOTHERBOARD' | 'RAM' | 'GPU' | 'STORAGE' | 'PSU' | 'CASE' | 'COOLER';
export type AiChatAnswerType = 'BUDGET' | 'PART' | 'GENERAL';
export type AiDraftActionType =
  | 'ADD_PART_TO_DRAFT'
  | 'REPLACE_DRAFT_PART'
  | 'REMOVE_DRAFT_PART'
  | 'UPDATE_DRAFT_QUANTITY'
  | 'ASK_FOLLOW_UP';
export type AiDraftActionStatus = 'PENDING' | 'APPLYING' | 'APPLIED' | 'FAILED';

export type AiToolResult = {
  tool: string;
  status: 'PASS' | 'WARN' | 'FAIL';
  confidence: 'LOW' | 'MEDIUM' | 'HIGH';
  summary: string;
  details?: Record<string, unknown>;
};

export type AiBuildItem = {
  partId: string;
  category: PartCategory;
  name: string;
  manufacturer: string;
  quantity: number;
  price: number;
  note: string;
};

export type AiRecommendedBuild = {
  id: string;
  tier: AiBuildTier;
  label: string;
  title: string;
  summary: string;
  totalPrice: number;
  badges: string[];
  budgetWon: number;
  budgetLabel: string;
  tierLabel: string;
  appliedPartCategories: PartCategory[];
  items: AiBuildItem[];
  toolResults?: AiToolResult[];
  warnings?: string[];
  confidence?: 'LOW' | 'MEDIUM' | 'HIGH';
};

export type AiSelectedBuild = Omit<AiRecommendedBuild, 'label' | 'badges'> & {
  selectedAt: string;
};

export type AiPartRecommendation = {
  category: PartCategory;
  label: string;
  intro: string;
  options: AiBuildItem[];
};

export type AiDraftAction = {
  id: string;
  type: AiDraftActionType;
  label: string;
  description?: string;
  payload: {
    partId?: string;
    category?: PartCategory;
    quantity?: number;
    source?: string;
    [key: string]: unknown;
  };
  requiresConfirmation?: boolean;
  status?: AiDraftActionStatus;
};

export type AiAppliedPartPreference = {
  category: PartCategory;
  label: string;
  appliedAt: string;
  options: AiBuildItem[];
};

export type AiChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  createdAt: string;
  kind: 'intro' | 'budget' | 'part' | 'general';
  budgetWon?: number;
  builds?: AiRecommendedBuild[];
  partRecommendation?: AiPartRecommendation | null;
  actions?: AiDraftAction[];
  warnings?: string[];
};

export type AiAssistantSession = {
  messages: AiChatMessage[];
  latestBuilds: AiRecommendedBuild[];
  appliedPartPreferences: AiAppliedPartPreference[];
  updatedAt: string;
};

export type AiBuildChatRequest = {
  message: string;
  currentBuilds?: AiRecommendedBuild[];
  appliedPartPreferences?: AiAppliedPartPreference[];
  currentQuoteDraft?: QuoteDraft;
};

export type AiBuildChatResponse = {
  answerType: AiChatAnswerType;
  message: string;
  builds: AiRecommendedBuild[];
  partRecommendation?: AiPartRecommendation | null;
  actions?: AiDraftAction[];
  warnings?: string[];
};

export const PART_CATEGORY_LABELS: Record<PartCategory, string> = {
  CPU: 'CPU',
  MOTHERBOARD: '메인보드',
  RAM: 'RAM',
  GPU: 'GPU',
  STORAGE: 'SSD',
  PSU: '파워',
  CASE: '케이스',
  COOLER: '쿨러'
};

const initialAssistantMessage: AiChatMessage = {
  id: 'ai-intro',
  role: 'assistant',
  text: '예산은 “200만원 PC 추천”처럼, 부품은 “GPU 추천해줘”처럼 물어보세요. 추천은 서버의 실제 부품 DB와 룰 기반 검증 결과로 계산됩니다.',
  createdAt: '2026-06-30T00:00:00.000Z',
  kind: 'intro'
};

export function emptyAssistantSession(): AiAssistantSession {
  return {
    messages: [initialAssistantMessage],
    latestBuilds: [],
    appliedPartPreferences: [],
    updatedAt: initialAssistantMessage.createdAt
  };
}

export function createAiMessageId(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export function toSelectedAiBuild(build: AiRecommendedBuild): AiSelectedBuild {
  const { label: _label, badges: _badges, ...selectedBuild } = normalizeAiRecommendedBuild(build);
  return {
    ...selectedBuild,
    selectedAt: new Date().toISOString()
  };
}

export function getAiStorageOwnerKey() {
  if (typeof window === 'undefined') return null;
  const cachedUser = getCachedAuthUser();
  if (!cachedUser || typeof cachedUser !== 'object') return null;
  const candidate = cachedUser as Record<string, unknown>;
  const id = typeof candidate.id === 'string' ? candidate.id.trim() : '';
  if (id) return id;
  const email = typeof candidate.email === 'string' ? candidate.email.trim().toLowerCase() : '';
  return email || null;
}

export function getScopedAiStorageKey(baseKey: string, ownerKey: string | null = getAiStorageOwnerKey()) {
  const normalizedOwnerKey = ownerKey?.trim();
  return normalizedOwnerKey ? `${baseKey}:${encodeURIComponent(normalizedOwnerKey)}` : null;
}

export function clearLegacyAiStorage() {
  if (typeof window === 'undefined') return;
  window.sessionStorage.removeItem(AI_SELECTED_BUILD_STORAGE_KEY);
  window.sessionStorage.removeItem(AI_ASSISTANT_SESSION_STORAGE_KEY);
}

export function saveSelectedAiBuild(build: AiRecommendedBuild, ownerKey: string | null = getAiStorageOwnerKey()) {
  if (typeof window === 'undefined') return;
  const storageKey = getScopedAiStorageKey(AI_SELECTED_BUILD_STORAGE_KEY, ownerKey);
  if (!storageKey) return;
  const selectedBuild = toSelectedAiBuild(normalizeAiRecommendedBuild(build));
  window.sessionStorage.setItem(storageKey, JSON.stringify(selectedBuild));
  window.dispatchEvent(new Event(AI_SELECTED_BUILD_CHANGED_EVENT));
}

export function readSelectedAiBuild(ownerKey: string | null = getAiStorageOwnerKey()): AiSelectedBuild | null {
  if (typeof window === 'undefined') return null;
  try {
    const storageKey = getScopedAiStorageKey(AI_SELECTED_BUILD_STORAGE_KEY, ownerKey);
    if (!storageKey) return null;
    const raw = window.sessionStorage.getItem(storageKey);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as AiSelectedBuild;
    const normalized = normalizeAiRecommendedBuild({
      ...parsed,
      label: parsed.tierLabel,
      badges: []
    });
    return {
      ...parsed,
      items: normalized.items,
      totalPrice: normalized.totalPrice
    };
  } catch {
    return null;
  }
}

export function clearSelectedAiBuild(ownerKey: string | null = getAiStorageOwnerKey()) {
  if (typeof window === 'undefined') return;
  const storageKey = getScopedAiStorageKey(AI_SELECTED_BUILD_STORAGE_KEY, ownerKey);
  if (!storageKey) return;
  window.sessionStorage.removeItem(storageKey);
  window.dispatchEvent(new Event(AI_SELECTED_BUILD_CHANGED_EVENT));
}

export function readAssistantSession(ownerKey: string | null = getAiStorageOwnerKey()): AiAssistantSession {
  if (typeof window === 'undefined') return emptyAssistantSession();
  try {
    const storageKey = getScopedAiStorageKey(AI_ASSISTANT_SESSION_STORAGE_KEY, ownerKey);
    if (!storageKey) return emptyAssistantSession();
    const raw = window.sessionStorage.getItem(storageKey);
    if (!raw) return emptyAssistantSession();
    const parsed = JSON.parse(raw) as AiAssistantSession;
    if (!Array.isArray(parsed.messages) || !Array.isArray(parsed.latestBuilds)) {
      return emptyAssistantSession();
    }
    return {
      messages: normalizeAssistantMessages(parsed.messages.length > 0 ? parsed.messages : [initialAssistantMessage]),
      latestBuilds: normalizeAiBuilds(parsed.latestBuilds ?? []),
      appliedPartPreferences: parsed.appliedPartPreferences ?? [],
      updatedAt: parsed.updatedAt ?? initialAssistantMessage.createdAt
    };
  } catch {
    return emptyAssistantSession();
  }
}

export function saveAssistantSession(session: AiAssistantSession, ownerKey: string | null = getAiStorageOwnerKey()) {
  if (typeof window === 'undefined') return;
  const storageKey = getScopedAiStorageKey(AI_ASSISTANT_SESSION_STORAGE_KEY, ownerKey);
  if (!storageKey) return;
  window.sessionStorage.setItem(storageKey, JSON.stringify(normalizeAssistantSession(session)));
  window.dispatchEvent(new Event(AI_ASSISTANT_SESSION_CHANGED_EVENT));
}

export function clearAssistantSession(ownerKey: string | null = getAiStorageOwnerKey()) {
  if (typeof window === 'undefined') return;
  const storageKey = getScopedAiStorageKey(AI_ASSISTANT_SESSION_STORAGE_KEY, ownerKey);
  if (!storageKey) return;
  window.sessionStorage.removeItem(storageKey);
  window.dispatchEvent(new Event(AI_ASSISTANT_SESSION_CHANGED_EVENT));
}

export function normalizeAiRecommendedBuild(build: AiRecommendedBuild): AiRecommendedBuild {
  const items = build.items.map((item) => ({
    ...item,
    quantity: Math.max(item.quantity ?? 1, defaultAiBuildQuantity(item.category))
  }));
  const titleMatch = build.title.trim().match(/^([\d,]+원)\s+(.+)$/);
  const normalizedTitle = titleMatch
    ? `${titleMatch[2].includes('추천') ? titleMatch[2] : `${titleMatch[2]} 추천 조합`}`
    : build.title;
  const normalizedBudgetLabel = titleMatch && build.budgetLabel === titleMatch[1]
    ? '예산 미지정'
    : build.budgetLabel;
  return {
    ...build,
    title: normalizedTitle,
    budgetLabel: normalizedBudgetLabel,
    items,
    totalPrice: items.reduce((sum, item) => sum + item.price * item.quantity, 0)
  };
}

export function normalizeAiBuilds(builds: AiRecommendedBuild[]) {
  return builds.map(normalizeAiRecommendedBuild);
}

function normalizeAssistantSession(session: AiAssistantSession): AiAssistantSession {
  return {
    ...session,
    messages: normalizeAssistantMessages(session.messages),
    latestBuilds: normalizeAiBuilds(session.latestBuilds)
  };
}

function normalizeAssistantMessages(messages: AiChatMessage[]) {
  return messages.map((message) => ({
    ...message,
    builds: message.builds ? normalizeAiBuilds(message.builds) : undefined
  }));
}

function defaultAiBuildQuantity(category: PartCategory) {
  return category === 'RAM' ? 2 : 1;
}
