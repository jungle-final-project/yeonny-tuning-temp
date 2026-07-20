import { api, ApiError } from '../../lib/api';
import type { AiBuildChatRequest, AiBuildChatResponse, AiRecommendedBuild, BuildGraphResolveRequest, BuildGraphResolveResponse } from './aiSelection';
import type { BuildSummary, ChangePartResponse, ParseRequirementPayload, ParsedRequirement, RecommendBuildResponse } from './types';

export type PriceAlert = {
  partId: string;
  partName: string;
  targetPrice: number;
  currentPrice: number;
  status: string;
  createdAt?: string;
};

export type PriceAlertsResponse = {
  items: PriceAlert[];
  page?: number;
  size?: number;
  total?: number;
};

export function parseRequirements(payload: ParseRequirementPayload) {
  return api<ParsedRequirement>('/api/requirements/parse', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function recommendBuild(requirementId: string, answers: Record<string, string> = {}) {
  return api<RecommendBuildResponse>('/api/builds/recommend', {
    method: 'POST',
    body: JSON.stringify({ requirementId, answers })
  });
}

export function getBuild(buildId: string) {
  return api<BuildSummary>(`/api/builds/${buildId}`);
}

export function getBuildHistory() {
  return api<{ items: BuildSummary[] }>('/api/builds/history');
}

export function renameBuild(buildId: string, name: string) {
  return api<BuildSummary>(`/api/builds/${buildId}`, {
    method: 'PATCH',
    body: JSON.stringify({ name })
  });
}

export function deleteBuild(buildId: string) {
  return api<{ id: string; deleted: boolean }>(`/api/builds/${buildId}`, {
    method: 'DELETE'
  });
}

export type SaveBuildFromChatPayload = {
  sourceBuildId: string;
  lastUserMessage?: string;
  build: AiRecommendedBuild;
};

export function saveBuildFromChat(payload: SaveBuildFromChatPayload) {
  return api<{ id: string }>('/api/builds/from-chat', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function buildSaveErrorMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 409) {
    return '이미 저장되었습니다.';
  }
  return '견적을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.';
}

export function getPriceAlerts() {
  return api<PriceAlertsResponse>('/api/price-alerts');
}

export function createQuotePriceAlert(partId: string, targetPrice: number) {
  return api<PriceAlert>('/api/price-alerts', {
    method: 'POST',
    body: JSON.stringify({ partId, targetPrice })
  });
}

export function changePart(buildId: string, category: string, partId: string) {
  return api<ChangePartResponse>(`/api/builds/${buildId}/change-part`, {
    method: 'POST',
    body: JSON.stringify({ category, partId })
  });
}

export function buildChat(payload: AiBuildChatRequest) {
  return api<AiBuildChatResponse>('/api/ai/build-chat', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export type HomeRecommendedBuildsResponse = {
  items: AiRecommendedBuild[];
  generatedAt: string;
  fallbackUsed: boolean;
};

export function listHomeRecommendedBuilds() {
  return api<HomeRecommendedBuildsResponse>('/api/recommendations/home-builds');
}

export function resolveBuildGraph(payload: BuildGraphResolveRequest) {
  return api<BuildGraphResolveResponse>('/api/build-graphs/resolve', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

// 게임별 FPS 참고범위 — 공개 자료 기반 참고값이며 정확 FPS를 보장하지 않는다(guaranteePolicy).
export type GameFpsEvidence = {
  gameTitle: string;
  gameKey: string;
  resolution: string;
  graphicsPreset?: string | null;
  avgFps?: number | null;
  onePercentLowFps?: number | null;
  sourceName?: string | null;
  confidence?: 'LOW' | 'MEDIUM' | 'HIGH';
  match?: {
    evidenceExactness?: string;
    resolutionMatched?: boolean;
    gameMatched?: boolean;
  };
};

export type PerformanceCheckResult = {
  tool: string;
  status: 'PASS' | 'WARN' | 'FAIL';
  confidence: string;
  summary: string;
  details?: {
    gameFpsEvidence?: GameFpsEvidence[];
    gameFpsEvidenceStatus?: string;
    guaranteePolicy?: string;
    [key: string]: unknown;
  };
};

// 기존 Tool 엔드포인트 재사용(신규 백엔드 없음): 담긴 견적의 CPU/GPU partIds + 게임·해상도 context로
// game_fps_benchmarks 공개 참고값을 조회한다.
export function checkBuildPerformance(payload: { partIds: string[]; game?: string; resolution?: string }) {
  return api<PerformanceCheckResult>('/api/tools/performance/check', {
    method: 'POST',
    body: JSON.stringify({
      partIds: payload.partIds,
      context: { game: payload.game, resolution: payload.resolution }
    })
  });
}
