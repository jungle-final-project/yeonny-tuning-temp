export type PartRow = {
  id: string;
  category: string;
  name: string;
  manufacturer?: string;
  price: number;
  status: string;
  attributes?: Record<string, unknown>;
  benchmarkSummary?: {
    summary?: string;
    score?: number | string;
  } | null;
  latestPriceSource?: string | null;
  latestPriceCollectedAt?: string | null;
  externalOffer?: {
    title?: string | null;
    imageUrl?: string | null;
    supplierName?: string | null;
    offerUrl?: string | null;
    lowPrice?: number | null;
    source?: string | null;
    refreshedAt?: string | null;
  } | null;
  score?: number;
};

export type PartPage = {
  items: PartRow[];
  page: number;
  size: number;
  total: number;
};

export type PartSearchParams = {
  category?: string;
  q?: string;
  manufacturer?: string;
  status?: string;
  minPrice?: number;
  maxPrice?: number;
  page?: number;
  size?: number;
  sort?: 'category' | 'price_asc' | 'price_desc' | 'name';
};

export type PartPriceHistoryPoint = {
  price: number;
  source: string;
  collectedAt: string;
};

export type PartPriceHistorySummary = {
  sampleCount: number;
  currentPrice: number;
  minPrice: number;
  maxPrice: number;
  firstPrice: number;
  lastPrice: number;
  changeAmount: number;
  changeRatePercent: number;
};

export type PartPriceHistory = {
  partId: string;
  partName: string;
  currentPrice: number;
  days: number;
  source?: string | null;
  items: PartPriceHistoryPoint[];
  summary: PartPriceHistorySummary;
};

export type PartPriceHistoryParams = {
  days?: number;
  source?: string;
  limit?: number;
};

export type ToolRow = {
  tool: string;
  status: string;
  score?: number;
  confidence: string;
  summary: string;
  warnings?: string[];
  evidence?: Array<{ source_id?: string; sourceId?: string; summary?: string }>;
  details?: Record<string, unknown>;
};
