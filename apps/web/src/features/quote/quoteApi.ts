import { api } from '../../lib/api';
import type { AiBuildChatRequest, AiBuildChatResponse, BuildGraphResolveRequest, BuildGraphResolveResponse } from './aiSelection';
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

export function resolveBuildGraph(payload: BuildGraphResolveRequest) {
  return api<BuildGraphResolveResponse>('/api/build-graphs/resolve', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}
