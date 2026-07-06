import type { QuoteDraft } from '../parts/types';
import { getCachedAuthUser } from '../../lib/api';

export const AI_SELECTED_BUILD_STORAGE_KEY = 'buildgraph.ai.selectedBuild';
export const AI_SELECTED_BUILD_CHANGED_EVENT = 'buildgraph.ai.selectedBuildChanged';
export const AI_ASSISTANT_SESSION_STORAGE_KEY = 'buildgraph.ai.assistantSession';
export const AI_ASSISTANT_SESSION_CHANGED_EVENT = 'buildgraph.ai.assistantSessionChanged';
export const AI_ASSISTANT_BUILD_HISTORY_LIMIT = 9;
export const AI_ASSISTANT_BUILD_CONTEXT_LIMIT = 3;

export type AiBuildTier = 'budget' | 'balanced' | 'performance';
export type PartCategory = 'CPU' | 'MOTHERBOARD' | 'RAM' | 'GPU' | 'STORAGE' | 'PSU' | 'CASE' | 'COOLER';
export type AiChatAnswerType = 'BUDGET' | 'PART' | 'GENERAL';
export type BuildGraphSource = 'AI_BUILD' | 'QUOTE_DRAFT_CURRENT';
export type BuildGraphView = 'FOCUSED' | 'FULL';
export type BuildGraphMode = 'BUILD_OVERVIEW' | 'PART_IMPACT' | 'ISSUE_PATH' | 'DRAFT_ACTION';
export type BuildGraphNodeType = 'PART' | 'CONSTRAINT' | 'ISSUE' | 'ACTION';
export type BuildGraphEdgeType = 'REQUIRES' | 'AFFECTS' | 'BLOCKS' | 'SUGGESTS';
export type BuildGraphStatus = 'PASS' | 'WARN' | 'FAIL';
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

export type AiSimulationPart = {
  partId?: string | null;
  category: PartCategory;
  name: string;
  manufacturer?: string | null;
  price?: number | null;
};

export type AiSimulationScoreComparison = {
  label: string;
  currentScore?: number | null;
  targetScore?: number | null;
  delta?: number | null;
};

export type AiSimulationFpsComparison = {
  gameTitle: string;
  resolution: string;
  graphicsPreset?: string | null;
  currentFps?: number | null;
  targetFps?: number | null;
  deltaFps?: number | null;
  source?: string | null;
};

export type AiSimulationSpecComparison = {
  label: string;
  currentValue?: string | null;
  targetValue?: string | null;
  deltaText?: string | null;
};

export type AiPerformanceSimulation = {
  type: 'PERFORMANCE_COMPARISON';
  category: PartCategory;
  currentPart: AiSimulationPart;
  targetPart: AiSimulationPart;
  summary: string;
  scoreComparison?: AiSimulationScoreComparison | null;
  fpsComparisons?: AiSimulationFpsComparison[];
  specComparisons?: AiSimulationSpecComparison[];
  warnings?: string[];
  disclaimer?: string;
};

export type BuildGraphFocus = {
  mode: BuildGraphMode;
  category?: PartCategory;
  partId?: string;
  tool?: 'compatibility' | 'power' | 'size' | 'performance' | 'price';
};

export type BuildGraphNode = {
  id: string;
  type: BuildGraphNodeType;
  category?: PartCategory | 'PRICE' | string;
  label: string;
  status: BuildGraphStatus;
  detail?: string;
  partId?: string;
  price?: number;
  position?: { x: number; y: number };
};

export type BuildGraphEdge = {
  id: string;
  source: string;
  target: string;
  type: BuildGraphEdgeType;
  status: BuildGraphStatus;
  label: string;
  summary: string;
};

export type BuildGraphInsight = {
  id: string;
  status: BuildGraphStatus;
  title: string;
  description: string;
  relatedNodeIds: string[];
};

export type BuildGraphResolveRequest = {
  source: BuildGraphSource;
  view?: BuildGraphView;
  items?: Array<Pick<AiBuildItem, 'partId' | 'category' | 'quantity'>>;
  budgetWon?: number;
  focus?: BuildGraphFocus;
};

export type BuildGraphResolveResponse = {
  mode: BuildGraphMode;
  summary: string;
  nodes: BuildGraphNode[];
  edges: BuildGraphEdge[];
  focusNodeIds: string[];
  insights: BuildGraphInsight[];
  toolResults: AiToolResult[];
};

export type AiChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  createdAt: string;
  kind: 'intro' | 'budget' | 'part' | 'general';
  budgetWon?: number;
  builds?: AiRecommendedBuild[];
  simulation?: AiPerformanceSimulation | null;
  warnings?: string[];
  quickReplies?: string[];
};

export type AiAssistantSession = {
  messages: AiChatMessage[];
  latestBuilds: AiRecommendedBuild[];
  savedBuildIds: Record<string, string>;
  latestGraphFocus?: BuildGraphFocus;
  latestActiveBuildId?: string;
  updatedAt: string;
};

export type AiBuildChatRequest = {
  message: string;
  currentBuilds?: AiRecommendedBuild[];
  currentQuoteDraft?: QuoteDraft;
  /** 직전 되묻기(clarification)에 대한 답변임을 알리는 에코 — 서버가 원 요청과 합성한다. */
  clarificationContext?: { originalMessage: string };
};

export type AiBuildChatResponse = {
  answerType: AiChatAnswerType;
  message: string;
  builds: AiRecommendedBuild[];
  simulation?: AiPerformanceSimulation | null;
  warnings?: string[];
  /** 모호 요청 되묻기 시 함께 오는 선택지 칩 — 그 자체로 완전한 프롬프트다. */
  quickReplies?: string[];
  clarification?: { missingSlots: string[]; originalMessage: string } | null;
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
  text: '예산 견적은 “200만원 게이밍 PC 추천”, 견적 완성은 “지금 견적 나머지 채워줘”, 성능 비교는 “CPU를 9700X로 바꾸면?”처럼 물어보세요. 추천은 서버의 실제 부품 DB와 룰 기반 검증 결과로 계산됩니다.',
  createdAt: '2026-06-30T00:00:00.000Z',
  kind: 'intro'
};

export function emptyAssistantSession(): AiAssistantSession {
  return {
    messages: [initialAssistantMessage],
    latestBuilds: [],
    savedBuildIds: {},
    latestGraphFocus: undefined,
    latestActiveBuildId: undefined,
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
      latestBuilds: mergeAiBuildHistory(parsed.latestBuilds ?? [], []),
      savedBuildIds: normalizeSavedBuildIds(parsed.savedBuildIds),
      latestGraphFocus: parsed.latestGraphFocus,
      latestActiveBuildId: parsed.latestActiveBuildId,
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

export function mergeAiBuildHistory(incomingBuilds: AiRecommendedBuild[], existingBuilds: AiRecommendedBuild[]) {
  const result: AiRecommendedBuild[] = [];
  const seen = new Set<string>();
  for (const build of [...normalizeAiBuilds(incomingBuilds), ...normalizeAiBuilds(existingBuilds)]) {
    const fingerprint = buildCompositionFingerprint(build);
    if (seen.has(fingerprint)) continue;
    seen.add(fingerprint);
    result.push(build);
    if (result.length >= AI_ASSISTANT_BUILD_HISTORY_LIMIT) break;
  }
  return result;
}

export function recentBuildsForChatContext(session: AiAssistantSession) {
  const latestAssistantBuilds = [...session.messages]
    .reverse()
    .find((message) => message.role === 'assistant' && message.builds?.length)
    ?.builds;
  return normalizeAiBuilds(latestAssistantBuilds ?? session.latestBuilds).slice(0, AI_ASSISTANT_BUILD_CONTEXT_LIMIT);
}

function normalizeAssistantSession(session: AiAssistantSession): AiAssistantSession {
  return {
    ...session,
    messages: normalizeAssistantMessages(session.messages),
    latestBuilds: mergeAiBuildHistory(session.latestBuilds, []),
    savedBuildIds: normalizeSavedBuildIds(session.savedBuildIds),
    latestGraphFocus: session.latestGraphFocus,
    latestActiveBuildId: session.latestActiveBuildId
  };
}

export function markAssistantBuildSaved(sourceBuildId: string, savedBuildId: string, ownerKey: string | null = getAiStorageOwnerKey()) {
  const session = readAssistantSession(ownerKey);
  const nextSession: AiAssistantSession = {
    ...session,
    savedBuildIds: {
      ...session.savedBuildIds,
      [sourceBuildId]: savedBuildId
    },
    updatedAt: new Date().toISOString()
  };
  saveAssistantSession(nextSession, ownerKey);
  return nextSession;
}

function normalizeSavedBuildIds(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return {};
  }
  const result: Record<string, string> = {};
  Object.entries(value as Record<string, unknown>).forEach(([key, item]) => {
    const sourceBuildId = key.trim();
    const savedBuildId = typeof item === 'string' ? item.trim() : '';
    if (sourceBuildId && savedBuildId) {
      result[sourceBuildId] = savedBuildId;
    }
  });
  return result;
}

function normalizeAssistantMessages(messages: AiChatMessage[]) {
  return messages.map((message) => {
    const { actions: _legacyActions, partRecommendation: _legacyPartRecommendation, ...rest } =
      message as AiChatMessage & { actions?: unknown; partRecommendation?: unknown };
    return {
      ...rest,
      builds: message.builds ? normalizeAiBuilds(message.builds) : undefined
    };
  });
}

function buildCompositionFingerprint(build: AiRecommendedBuild) {
  return build.items
    .map((item) => `${item.category}:${item.partId}:${Math.max(item.quantity ?? 1, defaultAiBuildQuantity(item.category))}`)
    .sort()
    .join('|');
}

function defaultAiBuildQuantity(category: PartCategory) {
  return category === 'RAM' ? 2 : 1;
}
