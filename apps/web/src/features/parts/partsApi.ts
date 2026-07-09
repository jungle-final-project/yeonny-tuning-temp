import { api } from '../../lib/api';
import type { AiBuildItem } from '../quote/aiSelection';
import type {
  CompatiblePartCandidateRequest,
  CompatiblePartCandidateResponse,
  HomeRecommendedPartsResponse,
  PartPage,
  PartPriceHistory,
  PartPriceHistoryParams,
  PartSearchParams,
  PartRow,
  QuoteDraft,
  RecommendationEventRequest
} from './types';

export function listParts(params: PartSearchParams = {}) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value));
    }
  });
  const query = search.toString();
  return api<PartPage>(`/api/parts${query ? `?${query}` : ''}`);
}

export function listHomeRecommendedParts(limit = 4) {
  return api<HomeRecommendedPartsResponse>(`/api/recommendations/home-parts?limit=${limit}`);
}

export function recordRecommendationEvent(payload: RecommendationEventRequest) {
  return api('/api/recommendation-events', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function getPart(partId: string) {
  return api<PartRow>(`/api/parts/${partId}`);
}

export function listCompatiblePartCandidates(payload: CompatiblePartCandidateRequest) {
  return api<CompatiblePartCandidateResponse>('/api/parts/compatible-candidates', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function getPartPriceHistory(partId: string, params: PartPriceHistoryParams = {}) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value));
    }
  });
  const query = search.toString();
  return api<PartPriceHistory>(`/api/parts/${partId}/price-history${query ? `?${query}` : ''}`);
}

export function getCurrentQuoteDraft() {
  return api<QuoteDraft>('/api/quote-drafts/current');
}

export function putQuoteDraftItem(partId: string, quantity: number) {
  return api<QuoteDraft>(`/api/quote-drafts/current/items/${partId}`, {
    method: 'PUT',
    body: JSON.stringify({ quantity })
  });
}

export function patchQuoteDraftItem(partId: string, quantity: number) {
  return api<QuoteDraft>(`/api/quote-drafts/current/items/${partId}`, {
    method: 'PATCH',
    body: JSON.stringify({ quantity })
  });
}

export function deleteQuoteDraftItem(partId: string) {
  return api<QuoteDraft>(`/api/quote-drafts/current/items/${partId}`, {
    method: 'DELETE'
  });
}

export function applyAiBuildToQuoteDraft(payload: {
  buildId?: string;
  items: Array<Pick<AiBuildItem, 'partId' | 'category' | 'quantity'>>;
  conflictPolicy: 'REPLACE';
}) {
  return api<QuoteDraft>('/api/quote-drafts/current/apply-ai-build', {
    method: 'PUT',
    body: JSON.stringify(payload)
  });
}

export function runToolCheck(tool: 'compatibility' | 'power' | 'size' | 'performance' | 'price', payload: unknown) {
  return api(`/api/tools/${tool}/check`, {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function createPriceAlert(partId: string, targetPrice: number) {
  return api('/api/price-alerts', {
    method: 'POST',
    body: JSON.stringify({ partId, targetPrice })
  });
}
