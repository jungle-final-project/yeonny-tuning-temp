import { useEffect, useRef, useState } from 'react';
import { AUTH_CHANGED_EVENT } from '../../../lib/api';
import { getCurrentQuoteDraft } from '../../parts/partsApi';
import type { QuoteDraft, QuoteDraftItem } from '../../parts/types';
import {
  AI_ASSISTANT_SESSION_CHANGED_EVENT,
  createAiMessageId,
  getAiStorageOwnerKey,
  readAssistantSession,
  saveAssistantSession,
  DEFAULT_AI_DRAFT_PERFORMANCE_SELECTION,
  type AiDraftApplicationFeedback,
  type AiDraftPerformanceSelection,
  type AiRecommendedBuild
} from '../aiSelection';
import { checkBuildPerformance, resolveBuildGraph } from '../quoteApi';

const FEEDBACK_TTL_MS = 60_000;
const ANALYSIS_TIMEOUT_MS = 5_000;
const ANALYSIS_START_DELAY_MS = 100;
const PERFORMANCE_VIEW_STORAGE_PREFIX = 'buildgraph.ai.performance-view';

export type AiDraftPerformanceView = AiDraftPerformanceSelection & {
  sourceFingerprint: string;
  evidenceSettled: boolean;
  avgFps?: number;
  updatedAt: string;
};

/** 고주사율(120Hz) 기준 — 이 값 이상이면 "쾌적", 60 이상이면 "플레이 가능"으로 본다. */
const HIGH_REFRESH_FPS = 120;
const PLAYABLE_FPS = 60;
/** 낮은 해상도부터 — 요약 문장과 판정 모두 이 순서를 따른다. */
const RESOLUTION_ORDER = ['FHD', 'QHD', '4K'];

type ResolutionFps = { resolution: string; avgFps: number };

type ApplicationAnalysis =
  | {
      status: 'READY';
      score: number;
      maxScore: number;
      /** 근거가 있는 해상도만 낮은 순으로 담는다 — 없는 해상도는 문장에서 아예 언급하지 않는다. */
      resolutionFps: ResolutionFps[];
      hasBlockingFail: boolean;
      gameLabel: string;
    }
  | { status: 'STALE' | 'FAILED' | 'TIMEOUT' };

export function applicationKindForBuild(build: AiRecommendedBuild): AiDraftApplicationFeedback['applicationKind'] {
  return build.badges.includes('DRAFT_EDIT_PREVIEW') ? 'PARTIAL_CHANGE' : 'COMPLETE_BUILD';
}

export function quoteDraftFingerprint(draftOrItems: QuoteDraft | QuoteDraftItem[]) {
  const items = Array.isArray(draftOrItems) ? draftOrItems : draftOrItems.items;
  return items
    .map((item) => `${item.category}:${item.partId}:${item.quantity}`)
    .sort()
    .join('|') || 'empty';
}

export function rememberAiDraftPerformanceView(view: AiDraftPerformanceView) {
  const ownerKey = getAiStorageOwnerKey();
  if (!ownerKey) return;
  try {
    sessionStorage.setItem(`${PERFORMANCE_VIEW_STORAGE_PREFIX}:${ownerKey}`, JSON.stringify(view));
  } catch {
    // Storage가 차단돼도 견적 적용과 기본 배그/4K 재조회는 계속 동작한다.
  }
}

export function startAiDraftApplicationFeedback({
  draft,
  applicationKind,
  activeBuildId,
  changeNote
}: {
  draft: QuoteDraft;
  applicationKind: AiDraftApplicationFeedback['applicationKind'];
  activeBuildId?: string;
  /** 담기/수량 변경처럼 어떤 상품이 몇 개가 됐는지 영수증 첫 줄에 그대로 남길 짧은 문구. */
  changeNote?: string;
}) {
  const ownerKey = getAiStorageOwnerKey();
  if (!ownerKey) return undefined;

  const session = readAssistantSession(ownerKey);
  const previous = session.draftApplicationFeedback;
  const messages = previous?.status === 'PENDING'
    ? session.messages.filter((message) => message.id !== previous.messageId)
    : session.messages;
  const startedAt = new Date().toISOString();
  const feedback: AiDraftApplicationFeedback = {
    id: createAiMessageId('draft-feedback'),
    messageId: createAiMessageId('draft-feedback-status'),
    draftFingerprint: quoteDraftFingerprint(draft),
    applicationKind,
    changeNote: changeNote?.trim() || undefined,
    status: 'PENDING',
    startedAt,
    performanceView: readAiDraftPerformanceView()
  };

  saveAssistantSession({
    ...session,
    messages: [...messages, {
      id: feedback.messageId,
      role: 'assistant',
      text: withChangeNote(feedback.changeNote, '견적 반영이 완료되었습니다. 종합 점수와 게임 성능을 확인하고 있습니다.'),
      createdAt: startedAt,
      kind: 'part'
    }],
    latestActiveBuildId: activeBuildId ?? session.latestActiveBuildId,
    draftApplicationFeedback: feedback,
    updatedAt: startedAt
  }, ownerKey);
  return feedback;
}

export function AiDraftApplicationFeedbackCoordinator() {
  const [feedback, setFeedback] = useState(() => readAssistantSession().draftApplicationFeedback);
  const inFlightIdRef = useRef<string | null>(null);

  useEffect(() => {
    const sync = () => setFeedback(readAssistantSession().draftApplicationFeedback);
    window.addEventListener(AI_ASSISTANT_SESSION_CHANGED_EVENT, sync);
    window.addEventListener(AUTH_CHANGED_EVENT, sync);
    window.addEventListener('storage', sync);
    return () => {
      window.removeEventListener(AI_ASSISTANT_SESSION_CHANGED_EVENT, sync);
      window.removeEventListener(AUTH_CHANGED_EVENT, sync);
      window.removeEventListener('storage', sync);
    };
  }, []);

  useEffect(() => {
    if (!feedback || feedback.status !== 'PENDING' || inFlightIdRef.current === feedback.id) return;
    const startTimer = window.setTimeout(() => {
      if (inFlightIdRef.current === feedback.id) return;
      inFlightIdRef.current = feedback.id;
      void analyzeWithTimeout(feedback)
        .then((analysis) => completeFeedback(feedback, analysis))
        .finally(() => {
          if (inFlightIdRef.current === feedback.id) inFlightIdRef.current = null;
        });
    }, ANALYSIS_START_DELAY_MS);
    return () => window.clearTimeout(startTimer);
  }, [feedback]);

  return null;
}

async function analyzeWithTimeout(feedback: AiDraftApplicationFeedback): Promise<ApplicationAnalysis> {
  if (Date.now() - Date.parse(feedback.startedAt) > FEEDBACK_TTL_MS) return { status: 'TIMEOUT' };

  let timeoutId: number | undefined;
  const timeout = new Promise<ApplicationAnalysis>((resolve) => {
    timeoutId = window.setTimeout(() => resolve({ status: 'TIMEOUT' }), ANALYSIS_TIMEOUT_MS);
  });
  const result = await Promise.race([analyzeCurrentDraft(feedback), timeout]);
  if (timeoutId !== undefined) window.clearTimeout(timeoutId);
  return result;
}

async function analyzeCurrentDraft(feedback: AiDraftApplicationFeedback): Promise<ApplicationAnalysis> {
  try {
    const draft = await getCurrentQuoteDraft();
    if (quoteDraftFingerprint(draft) !== feedback.draftFingerprint) return { status: 'STALE' };
    const performanceView = feedback.performanceView ?? readAiDraftPerformanceView();

    const performancePartIds = draft.items
      .filter((item) => item.category === 'CPU' || item.category === 'GPU')
      .map((item) => item.partId);
    const hasGpu = draft.items.some((item) => item.category === 'GPU');
    const [graphResult, performanceResult] = await Promise.allSettled([
      resolveBuildGraph({ source: 'QUOTE_DRAFT_CURRENT', view: 'FOCUSED', focus: { mode: 'ISSUE_PATH' } }),
      hasGpu && performancePartIds.length > 0
        ? checkBuildPerformance({
            partIds: performancePartIds,
            game: performanceView.gameQuery,
            resolution: performanceView.resolutionQuery
          })
        : Promise.resolve(null)
    ]);

    const latestDraft = await getCurrentQuoteDraft();
    if (quoteDraftFingerprint(latestDraft) !== feedback.draftFingerprint) return { status: 'STALE' };
    if (graphResult.status !== 'fulfilled' || !graphResult.value.compositeScore) return { status: 'FAILED' };

    const score = Math.round(graphResult.value.compositeScore.score);
    const maxScore = Math.round(graphResult.value.compositeScore.maxScore);
    const hasBlockingFail = score <= 0 || graphResult.value.toolResults.some((result) => result.status === 'FAIL');
    // 한 번의 조회가 같은 조합의 여러 해상도 근거를 함께 돌려준다(FHD/QHD/4K) — 추가 요청 없이 전부 읽는다.
    const evidenceRows = performanceResult.status === 'fulfilled'
      ? performanceResult.value?.details?.gameFpsEvidence ?? []
      : [];
    return {
      status: 'READY',
      score,
      maxScore,
      resolutionFps: resolutionFpsList(evidenceRows),
      hasBlockingFail,
      gameLabel: performanceView.gameLabel
    };
  } catch {
    return { status: 'FAILED' };
  }
}

function completeFeedback(feedback: AiDraftApplicationFeedback, analysis: ApplicationAnalysis) {
  const ownerKey = getAiStorageOwnerKey();
  if (!ownerKey) return;
  const session = readAssistantSession(ownerKey);
  const pending = session.draftApplicationFeedback;
  if (!pending || pending.id !== feedback.id || pending.status !== 'PENDING') return;

  const completedAt = new Date().toISOString();
  const text = withChangeNote(feedback.changeNote, feedbackText(feedback.applicationKind, analysis));
  saveAssistantSession({
    ...session,
    messages: session.messages.map((message) => message.id === feedback.messageId
      ? { ...message, text }
      : message),
    draftApplicationFeedback: {
      ...pending,
      status: 'CONSUMED',
      completedAt
    },
    updatedAt: completedAt
  }, ownerKey);
}

// 담기/수량 변경 에코("삼성 990 PRO 추가됨 · 현재 수량 2개")를 영수증 첫 줄로 보존한다.
// 점수/FPS 분석 결과가 어느 분기로 끝나든(성공·0점·근거 없음·STALE) 상품·수량 정보는 사라지지 않는다.
function withChangeNote(changeNote: string | undefined, text: string) {
  return changeNote ? `${changeNote}\n${text}` : text;
}

function feedbackText(
  applicationKind: AiDraftApplicationFeedback['applicationKind'],
  analysis: ApplicationAnalysis
) {
  if (analysis.status === 'STALE') {
    return '견적 반영 후 구성이 다시 변경되었습니다. 최신 종합 점수와 게임 성능은 상단에서 확인해 주세요.';
  }
  if (analysis.status !== 'READY') {
    return '견적 반영이 완료되었습니다. 종합 점수와 게임 성능은 상단에서 확인해 주세요.';
  }
  if (analysis.hasBlockingFail) {
    return '변경은 반영됐지만 호환성 또는 장착 문제로 종합 점수는 0점입니다. 게임 성능보다 상단 경고를 먼저 확인해 주세요.';
  }

  const prefix = applicationKind === 'COMPLETE_BUILD'
    ? '완성 견적이 담겼습니다.'
    : '요청한 변경이 반영되었습니다.';
  const scoreText = `현재 종합 점수는 ${analysis.score.toLocaleString('ko-KR')}점입니다.`;
  const closing = '\n\n상단에서 종합 점수와 게임별 예상 성능을 확인해 보세요.';
  // 근거가 없으면 성능 문장을 통째로 생략한다 — "자료 없음"을 굳이 알리지 않는다.
  if (analysis.resolutionFps.length === 0) {
    return `${prefix} ${scoreText}${closing}`;
  }
  const fpsText = analysis.resolutionFps
    .map((entry) => `${entry.resolution} ${entry.avgFps.toLocaleString('ko-KR')}`)
    .join(' · ');
  return `${prefix} ${scoreText} ${analysis.gameLabel} 예상 성능은 ${fpsText}FPS입니다. ${refreshRateNote(analysis.resolutionFps)}${closing}`;
}

/**
 * 해상도별 FPS를 120Hz 기준으로 해석한다 — 최소 견적에 4K 하나만 보여주던 문구가
 * "고사양 기준으로 평가"처럼 읽히던 문제를 없앤다(2026-07-20 팀 제언).
 * 항상 "가능한 것" 먼저, "한계"는 뒤에 붙인다.
 */
function refreshRateNote(entries: ResolutionFps[]) {
  const smooth = entries.filter((entry) => entry.avgFps >= HIGH_REFRESH_FPS);
  const below = entries.filter((entry) => entry.avgFps < HIGH_REFRESH_FPS);
  if (smooth.length > 0 && below.length > 0) {
    return `${smooth.map((entry) => entry.resolution).join('·')}는 120Hz 이상으로 쾌적하고, ${below.map((entry) => entry.resolution).join('·')}는 120Hz에 못 미치니 참고하세요.`;
  }
  if (below.length === 0) {
    return '모든 해상도에서 120Hz 이상으로 쾌적합니다.';
  }
  // 전 구간 120 미만 — 가장 낮은 해상도 기준으로 "할 수 있는 것"을 먼저 말한다.
  const best = entries[0];
  if (best.avgFps >= PLAYABLE_FPS) {
    return `${best.resolution} ${best.avgFps.toLocaleString('ko-KR')}FPS로 플레이하기 충분하지만, 120Hz 고주사율에는 못 미칩니다.`;
  }
  return '옵션을 낮추거나 더 낮은 해상도로 즐기기를 권합니다.';
}

/** 근거 행에서 해상도별 대표 FPS를 뽑아 낮은 해상도 순으로 정렬한다(중복 해상도는 첫 근거만). */
function resolutionFpsList(rows: Array<{ resolution?: string | null; avgFps?: number | null }>): ResolutionFps[] {
  const byResolution = new Map<string, number>();
  rows.forEach((row) => {
    const resolution = typeof row.resolution === 'string' ? row.resolution.trim().toUpperCase() : '';
    const avgFps = Number(row.avgFps);
    if (!RESOLUTION_ORDER.includes(resolution) || !Number.isFinite(avgFps) || avgFps <= 0) return;
    if (byResolution.has(resolution)) return;
    byResolution.set(resolution, Math.round(avgFps));
  });
  return RESOLUTION_ORDER
    .filter((resolution) => byResolution.has(resolution))
    .map((resolution) => ({ resolution, avgFps: byResolution.get(resolution) as number }));
}

function readAiDraftPerformanceView(): AiDraftPerformanceSelection {
  const fallback = { ...DEFAULT_AI_DRAFT_PERFORMANCE_SELECTION };
  const ownerKey = getAiStorageOwnerKey();
  if (!ownerKey) return fallback;
  try {
    const raw = sessionStorage.getItem(`${PERFORMANCE_VIEW_STORAGE_PREFIX}:${ownerKey}`);
    if (!raw) return fallback;
    const parsed = JSON.parse(raw) as Partial<AiDraftPerformanceView>;
    if (!parsed.gameLabel || !parsed.gameQuery || !parsed.resolutionLabel || !parsed.resolutionQuery) return fallback;
    return {
      gameLabel: parsed.gameLabel,
      gameQuery: parsed.gameQuery,
      resolutionLabel: parsed.resolutionLabel,
      resolutionQuery: parsed.resolutionQuery
    };
  } catch {
    return fallback;
  }
}
