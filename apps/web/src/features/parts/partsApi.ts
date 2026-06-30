import { api } from '../../lib/api';
import type { PartPage, PartPriceHistory, PartPriceHistoryParams, PartSearchParams, PartRow, ToolRow } from './types';

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

export function getPart(partId: string) {
  return api<PartRow>(`/api/parts/${partId}`);
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

export function runToolCheck(tool: 'compatibility' | 'power' | 'size' | 'performance' | 'price', payload: unknown) {
  return api<ToolRow>(`/api/tools/${tool}/check`, {
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
