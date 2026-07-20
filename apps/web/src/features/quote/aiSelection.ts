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
  /** 서버 응답 계약이 아닌, 자동 적용 카드의 변경 전후 표시를 위한 세션 로컬 snapshot. */
  displayChangeReceipt?: {
    beforeTotalPrice: number;
    afterTotalPrice: number;
    changes: Array<{
      category: PartCategory;
      beforeLabel: string;
      afterLabel: string;
    }>;
  };
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

export type BuildCompositeScoreComponent = {
  key: 'performance' | 'compatibility' | 'balance' | 'upgrade' | 'evidence' | string;
  label: string;
  score: number;
  maxScore: number;
  percent: number;
  summary: string;
};

export type BuildCompositeScoreCap = {
  code: string;
  maxScore: number;
  reason: string;
};

export type BuildCompositeRequestFit = {
  status: 'PASS' | 'WARN' | 'OVER_BUDGET' | 'UNSPECIFIED' | string;
  score: number;
  budgetWon?: number;
  totalPrice?: number;
  priceDiff?: number;
  summary: string;
};

export type BuildCompositeScore = {
  policyVersion: string;
  score: number;
  rawScore: number;
  maxScore: number;
  grade: string;
  label: string;
  summary: string;
  components: BuildCompositeScoreComponent[];
  caps: BuildCompositeScoreCap[];
  requestFit?: BuildCompositeRequestFit;
  curve?: { marker: number; label: string }[];
  missingCategories?: string[];
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
  compositeScore?: BuildCompositeScore;
  buildAssessment?: AiBuildAssessment;
  toolResults: AiToolResult[];
};

export type AiAssessmentContext = {
  source: 'QUOTE_DRAFT_CURRENT';
  focusType: 'SCORE' | 'ISSUE';
  category?: PartCategory;
  tool?: 'compatibility' | 'power' | 'size' | 'performance' | 'price';
};

export type AiBuildAssessmentItem = {
  code: string;
  severity: 'PASS' | 'WARN' | 'FAIL';
  title: string;
  description: string;
  relatedCategories: PartCategory[];
};

export type AiBuildAssessmentRecommendation = {
  priority: number;
  category: PartCategory;
  title: string;
  reason: string;
  prompt: string;
};

export type AiBuildAssessment = {
  type: 'COMPOSITE_SCORE_EXPLANATION';
  score: number;
  maxScore: number;
  grade: string;
  label: string;
  summary: string;
  strengths: AiBuildAssessmentItem[];
  cautions: AiBuildAssessmentItem[];
  recommendations: AiBuildAssessmentRecommendation[];
  evaluatedAt: string;
};

export type AiSupportGuidanceAction = {
  type: 'DOWNLOAD_PC_AGENT' | 'OPEN_SUPPORT_NEW';
  label: string;
  route?: '/support/new';
};

export type AiSupportGuidance = {
  type: 'PC_AGENT_DIAGNOSTIC_ENTRY';
  scope: 'PRE_DIAGNOSIS';
  symptomCategory: 'DISPLAY_FREEZE' | 'POWER_RESTART' | 'BOOT_FAILURE' | 'PERFORMANCE_STUTTER' | 'THERMAL_NOISE' | 'STORAGE' | 'NETWORK' | 'AUDIO' | 'GENERAL';
  title: string;
  summary: string;
  /** 신규 서버 응답에는 항상 포함되며, 필드 도입 전 로컬 세션 복원을 위해 optional로 읽는다. */
  possibleCauses?: string[];
  beforeDiagnosisChecks: string[];
  agentRecommendation: 'OPTIONAL' | 'RECOMMENDED';
  actions: AiSupportGuidanceAction[];
  disclaimer: string;
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
  buildAssessment?: AiBuildAssessment;
  supportGuidance?: AiSupportGuidance;
  warnings?: string[];
  quickReplies?: string[];
  /** 이 칩이 무엇을 뜻하는지. ROUTE_CHOICE면 "상품 하나 고르기"라서 다음 요청에 출처를 되보낸다. */
  quickReplyKind?: AiQuickReplyKind;
  /** RAM/SSD처럼 다중 상품을 허용하는 구체 추천 칩의 직접 견적 추가 메타데이터. */
  quickReplyCommands?: AiQuickReplyCommand[];
};

export type AiQuickReplyCommand = {
  label: string;
  type: 'ADD_MULTI_ITEM_TO_DRAFT';
  partId: string;
  partName: string;
  category: 'RAM' | 'STORAGE';
  quantityDelta: 1;
};

export type AiDraftPerformanceSelection = {
  gameLabel: string;
  gameQuery: string;
  resolutionLabel: string;
  resolutionQuery: string;
};

export const DEFAULT_AI_DRAFT_PERFORMANCE_SELECTION: Readonly<AiDraftPerformanceSelection> = {
  gameLabel: '배그',
  gameQuery: 'pubg',
  resolutionLabel: '4K',
  resolutionQuery: '4k'
};

export type AiDraftApplicationFeedback = {
  id: string;
  messageId: string;
  draftFingerprint: string;
  applicationKind: 'COMPLETE_BUILD' | 'PARTIAL_CHANGE';
  /** 담기/수량 변경처럼 어떤 상품이 몇 개가 됐는지 영수증 첫 줄에 그대로 에코할 짧은 문구. */
  changeNote?: string;
  status: 'PENDING' | 'CONSUMED';
  startedAt: string;
  completedAt?: string;
  performanceView?: AiDraftPerformanceSelection;
};

export type AiAssistantSession = {
  messages: AiChatMessage[];
  latestBuilds: AiRecommendedBuild[];
  savedBuildIds: Record<string, string>;
  latestGraphFocus?: BuildGraphFocus;
  latestActiveBuildId?: string;
  draftApplicationFeedback?: AiDraftApplicationFeedback;
  updatedAt: string;
};

export type AiBuildChatRequest = {
  message: string;
  currentBuilds?: AiRecommendedBuild[];
  currentQuoteDraft?: QuoteDraft;
  uiContext?: {
    surface: 'HOME' | 'SELF_QUOTE';
    /**
     * 이 클라이언트가 처리할 수 있는 것. 서버는 이걸 보고 응답 모양을 정한다.
     * - BOARD_PART_FOCUS: 보드에서 부품 위치를 강조할 수 있다
     * - PART_CANDIDATE_PANEL: 부품 목록 패널을 띄울 수 있다(상품 나열을 말풍선 대신 패널이 맡는다)
     */
    capabilities: Array<'BOARD_PART_FOCUS' | 'PART_CANDIDATE_PANEL'>;
  };
  assessmentContext?: AiAssessmentContext;
  /** 직전 되묻기(clarification)에 대한 답변임을 알리는 에코 — 서버가 원 요청과 합성한다. */
  clarificationContext?: { originalMessage: string };
  /**
   * 이 message가 사용자가 직접 친 문장이 아니라 되묻기 칩에서 온 것임을 알리는 표식.
   * ROUTE_CHOICE면 서버는 상품명 어휘를 해석하지 않고 그 턴을 상품 이동으로 확정한다.
   */
  quickReplySource?: AiQuickReplyKind;
};

export type AiBoardFocus = {
  type: 'PART_LOCATION';
  categories: PartCategory[];
  label: string;
};

/** 칩이 무엇을 뜻하는지. ROUTE_CHOICE = 후보 상품 중 하나 고르기(상세 이동 후보). */
export type AiQuickReplyKind = 'ROUTE_CHOICE';

/** 답변이 "이동할게요"라고 약속한 화면. 서버가 실제 상품·카테고리로 해상한 내부 경로만 담긴다. */
export type AiChatNavigationAction = {
  type: 'OPEN_ROUTE';
  label: string;
  payload: { route: string };
};

export type AiBuildChatResponse = {
  answerType: AiChatAnswerType;
  message: string;
  builds: AiRecommendedBuild[];
  /** 화면 이동 명령. 이게 없으면 답변 문구가 이동을 약속해도 아무 일도 일어나지 않는다. */
  actions?: AiChatNavigationAction[];
  /** 부품 추천 결과. 이게 있으면 부품 목록 패널을 그 카테고리로 연다(문장 안 상품명은 파싱할 수 없다). */
  partRecommendation?: AiPartRecommendation | null;
  simulation?: AiPerformanceSimulation | null;
  buildAssessment?: AiBuildAssessment | null;
  supportGuidance?: AiSupportGuidance | null;
  warnings?: string[];
  /** 모호 요청 되묻기 시 함께 오는 선택지 칩 — 그 자체로 완전한 프롬프트다. */
  quickReplies?: string[];
  /** quickReplies가 무엇을 뜻하는 칩인지. ROUTE_CHOICE = 상품 하나 고르기(상세 이동 후보). */
  quickReplyKind?: AiQuickReplyKind;
  /**
   * 구체 RAM/SSD 추천 칩의 직접 견적 추가 명령. 일반 자연어 변경 요청은 이 필드를 쓰지 않고
   * 기존 변경 미리보기 흐름을 유지한다.
   */
  quickReplyCommands?: AiQuickReplyCommand[];
  /** 셀프 견적 구성도에서만 소비하는 읽기 전용 부품 위치 강조 명령. */
  boardFocus?: AiBoardFocus | null;
  clarification?: { missingSlots: string[]; originalMessage: string } | null;
};

/**
 * 답변에 실려 온 이동 경로를 꺼낸다. 앱 내부 절대경로가 아니면 버린다 —
 * 프로토콜 상대 경로(`//다른주소`)나 외부 URL로 사용자를 내보내지 않기 위한 방어선이다.
 */
export function navigationRouteFrom(response: AiBuildChatResponse): string | null {
  const route = response.actions?.find((action) => action.type === 'OPEN_ROUTE')?.payload?.route;
  if (typeof route !== 'string') return null;
  const trimmed = route.trim();
  return trimmed.startsWith('/') && !trimmed.startsWith('//') ? trimmed : null;
}

export type AiPartRecommendationOption = {
  partId: string;
  name: string;
  price: number;
};

/** 챗봇이 고른 부품 후보. 부품 목록 패널이 이 순서대로 위에 올린다. */
export type AiPartRecommendation = {
  category: PartCategory;
  options: AiPartRecommendationOption[];
};

const AI_PART_PICKS_KEY = 'buildgraph.aiPartPicks';
/** 추천 순서가 바뀌었다는 신호. 패널이 이미 그 카테고리로 열려 있으면 주소가 안 바뀌어 이 신호로만 안다. */
export const AI_PART_PICKS_CHANGED_EVENT = 'buildgraph:ai-part-picks-changed';

/** 답변에 실려 온 추천 결과를 꺼낸다. 카테고리가 우리가 아는 8개가 아니면 버린다. */
export function partRecommendationFrom(response: AiBuildChatResponse): AiPartRecommendation | null {
  const raw = response.partRecommendation;
  if (!raw || typeof raw !== 'object') return null;
  const category = raw.category;
  if (!category || !(category in PART_CATEGORY_LABELS)) return null;
  const options = (Array.isArray(raw.options) ? raw.options : [])
    .filter((option): option is AiPartRecommendationOption => typeof option?.partId === 'string' && option.partId.length > 0);
  return options.length > 0 ? { category, options } : null;
}

/**
 * 추천 순서를 화면 이동 너머로 넘긴다. 홈에서 물으면 셀프견적으로 옮겨 가며 챗봇이 언마운트되므로
 * 메모리로는 전달할 수 없다. URL에 UUID를 나열하면 주소가 100자를 넘어 지저분해진다.
 * sessionStorage는 이 저장소가 이미 챗봇→화면 전달에 쓰는 방식이다(견적 비교 패널).
 */
export function rememberAiPartPicks(
  recommendation: AiPartRecommendation,
  ownerKey: string | null = getAiStorageOwnerKey()
) {
  // 이 순서는 "이 사용자의 현재 견적 기준으로 고른 결과"다 — 같은 탭에서 계정이 바뀌면
  // 남의 견적으로 뽑힌 순서가 된다. 다른 AI 저장소와 같이 소유자별로 쪼갠다.
  const storageKey = getScopedAiStorageKey(AI_PART_PICKS_KEY, ownerKey);
  if (!storageKey) return;
  try {
    sessionStorage.setItem(storageKey, JSON.stringify({
      category: recommendation.category,
      partIds: recommendation.options.map((option) => option.partId)
    }));
  } catch {
    // sessionStorage 접근 불가(프라이빗 모드 등)면 추천 순서만 포기한다 — 패널은 그대로 열린다.
    return;
  }
  // 패널이 이미 그 카테고리로 열려 있으면 주소가 안 바뀌어 리마운트도 이펙트도 없다.
  // 이 신호가 없으면 챗봇은 "띄웠어요"라고 말하는데 목록은 한 글자도 안 바뀐다.
  window.dispatchEvent(new Event(AI_PART_PICKS_CHANGED_EVENT));
}

/** 그 카테고리로 남겨 둔 추천 순서. 다른 카테고리를 열었으면 빈 배열이다. */
export function readAiPartPicks(
  category: PartCategory,
  ownerKey: string | null = getAiStorageOwnerKey()
): string[] {
  const storageKey = getScopedAiStorageKey(AI_PART_PICKS_KEY, ownerKey);
  if (!storageKey) return [];
  try {
    const raw = sessionStorage.getItem(storageKey);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as { category?: string; partIds?: unknown };
    if (parsed.category !== category || !Array.isArray(parsed.partIds)) return [];
    return parsed.partIds.filter((id): id is string => typeof id === 'string');
  } catch {
    return [];
  }
}

/**
 * 추천 고정을 푼다. 사용자가 정렬을 직접 바꾸면 목록의 주인은 사용자다 —
 * 지우지 않으면 다시 열 때 이미 지나간 추천이 조용히 되살아난다.
 */
export function clearAiPartPicks(ownerKey: string | null = getAiStorageOwnerKey()) {
  const storageKey = getScopedAiStorageKey(AI_PART_PICKS_KEY, ownerKey);
  if (!storageKey) return;
  try {
    sessionStorage.removeItem(storageKey);
  } catch {
    return;
  }
  window.dispatchEvent(new Event(AI_PART_PICKS_CHANGED_EVENT));
}

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
  text: '예산 견적은 “200만원 게이밍 PC 추천”, 견적 완성은 “지금 견적 나머지 채워줘”, 성능 비교는 “CPU를 9700X로 바꾸면?”처럼 물어보세요. 추천은 실제 부품 데이터와 검증 결과를 바탕으로 계산됩니다.',
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
    draftApplicationFeedback: undefined,
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
      draftApplicationFeedback: normalizeDraftApplicationFeedback(parsed.draftApplicationFeedback),
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

export function resetAssistantConversation(ownerKey: string | null = getAiStorageOwnerKey()) {
  const session = readAssistantSession(ownerKey);
  const nextSession: AiAssistantSession = {
    ...session,
    messages: [initialAssistantMessage],
    latestGraphFocus: undefined,
    latestActiveBuildId: undefined,
    draftApplicationFeedback: undefined,
    updatedAt: new Date().toISOString()
  };
  saveAssistantSession(nextSession, ownerKey);
  return nextSession;
}

export function normalizeAiRecommendedBuild(build: AiRecommendedBuild): AiRecommendedBuild {
  const items = build.items.map((item) => ({
    ...item,
    quantity: resolvedAiBuildQuantity(item.quantity, item.category)
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
    latestActiveBuildId: session.latestActiveBuildId,
    draftApplicationFeedback: normalizeDraftApplicationFeedback(session.draftApplicationFeedback)
  };
}

function normalizeDraftApplicationFeedback(value: unknown): AiDraftApplicationFeedback | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const candidate = value as Record<string, unknown>;
  const id = typeof candidate.id === 'string' ? candidate.id.trim() : '';
  const messageId = typeof candidate.messageId === 'string' ? candidate.messageId.trim() : '';
  const draftFingerprint = typeof candidate.draftFingerprint === 'string' ? candidate.draftFingerprint.trim() : '';
  const applicationKind = candidate.applicationKind === 'COMPLETE_BUILD' || candidate.applicationKind === 'PARTIAL_CHANGE'
    ? candidate.applicationKind
    : null;
  const status = candidate.status === 'PENDING' || candidate.status === 'CONSUMED' ? candidate.status : null;
  const startedAt = typeof candidate.startedAt === 'string' ? candidate.startedAt.trim() : '';
  const completedAt = typeof candidate.completedAt === 'string' && candidate.completedAt.trim()
    ? candidate.completedAt.trim()
    : undefined;
  const changeNote = typeof candidate.changeNote === 'string' && candidate.changeNote.trim()
    ? candidate.changeNote.trim()
    : undefined;
  const performanceView = normalizeDraftPerformanceSelection(candidate.performanceView);
  if (!id || !messageId || !draftFingerprint || !applicationKind || !status || !startedAt) return undefined;
  return { id, messageId, draftFingerprint, applicationKind, changeNote, status, startedAt, completedAt, performanceView };
}

function normalizeDraftPerformanceSelection(value: unknown): AiDraftPerformanceSelection | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const candidate = value as Record<string, unknown>;
  const gameLabel = typeof candidate.gameLabel === 'string' ? candidate.gameLabel.trim() : '';
  const gameQuery = typeof candidate.gameQuery === 'string' ? candidate.gameQuery.trim() : '';
  const resolutionLabel = typeof candidate.resolutionLabel === 'string' ? candidate.resolutionLabel.trim() : '';
  const resolutionQuery = typeof candidate.resolutionQuery === 'string' ? candidate.resolutionQuery.trim() : '';
  if (!gameLabel || !gameQuery || !resolutionLabel || !resolutionQuery) return undefined;
  return { gameLabel, gameQuery, resolutionLabel, resolutionQuery };
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
    .map((item) => `${item.category}:${item.partId}:${resolvedAiBuildQuantity(item.quantity, item.category)}`)
    .sort()
    .join('|');
}

function defaultAiBuildQuantity(category: PartCategory) {
  return category === 'RAM' ? 2 : 1;
}

// 서버가 수량을 명시하면 그대로 존중하고(예: 드래프트 유지 RAM 1개), 미지정일 때만 카테고리 기본값을 채운다.
function resolvedAiBuildQuantity(quantity: number | undefined, category: PartCategory) {
  return typeof quantity === 'number' && quantity >= 1 ? quantity : defaultAiBuildQuantity(category);
}
