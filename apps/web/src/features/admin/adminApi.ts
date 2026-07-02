import { api } from '../../lib/api';

export type AdminDashboard = {
  agentRunning: number;
  openTickets: number;
  priceJobsRunning: number;
  degraded: boolean;
  generatedAt?: string;
};

export type AdminAuditLog = {
  action?: string;
  targetType?: string;
  targetId?: string | null;
  metadata?: Record<string, unknown> | null;
  createdAt?: string;
};

export type AdminAuditLogsResponse = {
  items: AdminAuditLog[];
};

export type AgentTimelineItem = {
  from: string | null;
  to: string;
  at?: string;
  actor: string;
  reason?: string;
};

export type ToolInvocation = {
  id: string;
  agentSessionId: string;
  toolName: string;
  status: string;
  confidence: string;
  summary: string;
  requestPayload?: Record<string, unknown>;
  resultPayload?: Record<string, unknown>;
  latencyMs?: number | null;
  createdAt?: string;
};

export type LlmGeneration = {
  id: string;
  aiProfile: string;
  provider: string;
  model: string;
  reasoningEffort?: string | null;
  useCase: string;
  status: string;
  schemaName?: string | null;
  latencyMs?: number | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  schemaValid?: boolean;
  errorCode?: string | null;
  createdAt?: string;
};

export type AgentSessionDetail = {
  id: string;
  status: string;
  summary: string | null;
  stateTimeline: AgentTimelineItem[];
  purpose?: string;
  toolInvocations: ToolInvocation[];
  evidenceIds: string[];
  llmGenerations?: LlmGeneration[];
};

export type AgentSessionSummary = {
  id: string;
  status: string;
  userId?: string;
  createdAt?: string;
};

export type AgentSessionsResponse = {
  items: AgentSessionSummary[];
  page?: number;
  size?: number;
  total?: number;
};

export type ToolInvocationsResponse = {
  items: ToolInvocation[];
  page?: number;
  size?: number;
  total?: number;
};

export type RagEvidenceDetail = {
  id: string;
  agentSessionId?: string;
  sourceId: string;
  chunkText?: string;
  summary: string;
  score?: string | number | null;
  metadata?: Record<string, unknown>;
};

export type RagEvidenceResponse = {
  items: RagEvidenceDetail[];
  page?: number;
  size?: number;
  total?: number;
};

export type AsTicketStatus = 'OPEN' | 'ASSIGNED' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED' | 'CANCELLED';
export type AsReviewStatus = 'NOT_REQUIRED' | 'REQUIRED' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED';
export type AsSupportDecision = 'SELF_SOLVABLE' | 'REMOTE_POSSIBLE' | 'VISIT_REQUIRED' | 'NEEDS_MORE_INFO';
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type AdminAsTicket = {
  id: string;
  userId?: string | null;
  status: AsTicketStatus;
  analysisStatus?: string | null;
  reviewStatus?: AsReviewStatus | string | null;
  supportDecision?: AsSupportDecision | string | null;
  riskLevel?: RiskLevel | string | null;
  autoResponseAllowed?: boolean | null;
  symptom: string;
  title?: string | null;
  description?: string | null;
  detailDescription?: string | null;
  logUploadId?: string | null;
  logSummary?: string | null;
  userEmail?: string | null;
  userName?: string | null;
  assignedAdminId?: string | null;
  causeCandidates: Record<string, unknown>[];
  upgradeCandidates: Record<string, unknown>[];
  adminNote?: string | null;
  resolvedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
};

export type AdminTicket = AdminAsTicket;

export type AdminTicketsResponse = {
  items: AdminAsTicket[];
};

export type AdminAsTicketUpdateRequest = {
  status?: AsTicketStatus;
  assignedAdminId?: string | null;
  adminNote?: string | null;
  reviewStatus?: AsReviewStatus | string | null;
  supportDecision?: AsSupportDecision | string | null;
  riskLevel?: RiskLevel | string | null;
  autoResponseAllowed?: boolean | null;
};

export type AdminTicketPayload = AdminAsTicketUpdateRequest;

export type PriceJob = {
  id: string;
  status: string;
  requestedBy?: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  errorSummary?: string | null;
  createdAt?: string;
};

export type PriceJobsResponse = {
  items: PriceJob[];
  page?: number;
  size?: number;
  total?: number;
};

export type ManufacturerSource = {
  id: string;
  manufacturer: string;
  categoryScope: string;
  sourceType: string;
  sourceUrl: string;
  enabled: boolean;
  pollIntervalMinutes: number;
  lastCheckedAt?: string | null;
  parserConfig?: Record<string, unknown> | string | null;
  status: string;
  errorSummary?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  deletedAt?: string | null;
};

export type ManufacturerSourcesResponse = {
  items: ManufacturerSource[];
};

export type ManufacturerPost = {
  id: string;
  sourceId?: string;
  manufacturer: string;
  sourceUrl?: string;
  externalUrl: string;
  title: string;
  publishedAt?: string | null;
  excerpt?: string | null;
  classificationStatus: string;
  detectedCategory?: string | null;
  detectedProductName?: string | null;
  confidence?: number | string | null;
  catalogCandidateId?: string | null;
  updatedAt?: string | null;
  createdAt?: string;
  deletedAt?: string | null;
};

export type ManufacturerPostsResponse = {
  items: ManufacturerPost[];
  page: number;
  size: number;
  total: number;
};

export type PartCatalogCandidate = {
  id: string;
  source: string;
  category: string;
  sourceProductKey?: string;
  searchQuery: string;
  title: string;
  manufacturerGuess?: string | null;
  imageUrl?: string | null;
  supplierName?: string | null;
  offerUrl?: string | null;
  lowPrice?: number | null;
  candidateStatus: string;
  publishedPartId?: string | null;
  publishedPartStatus?: string | null;
  rawPayload?: Record<string, unknown> | string | null;
  discoveredAt?: string;
  lastSeenAt?: string;
  updatedAt?: string | null;
  deletedAt?: string | null;
};

export type PartCatalogCandidatesResponse = {
  items: PartCatalogCandidate[];
  page: number;
  size: number;
  total: number;
};

export type PartAliasReviewItem = {
  id: string;
  sourceType: string;
  category?: string | null;
  targetField?: string | null;
  aliasText?: string | null;
  rawValue?: string | null;
  canonicalSuggestion?: string | null;
  message?: string | null;
  status: string;
  resolutionNote?: string | null;
  partId?: string | null;
  partName?: string | null;
  resolvedAliasRuleId?: string | null;
  createdAt?: string | null;
};

export type PartAliasReviewItemsResponse = {
  items: PartAliasReviewItem[];
  page: number;
  size: number;
  total: number;
};

export type PartAliasReviewItemsParams = {
  status?: string;
  category?: string;
  targetField?: string;
  sourceType?: string;
  page?: number;
  size?: number;
};

export type PartAliasReviewSummaryResponse = {
  items: Array<{
    category?: string | null;
    targetField?: string | null;
    sourceType?: string | null;
    count: number;
  }>;
};

export type PartAliasRule = {
  id: string;
  category: string;
  targetField: string;
  aliasText: string;
  canonicalValue: string;
  status: string;
  source?: string;
  note?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
};

export type PartAliasRulesResponse = {
  items: PartAliasRule[];
  page: number;
  size: number;
  total: number;
};

export type ManufacturerSourceScanResponse = {
  sourceId: string;
  manufacturer?: string;
  failed?: boolean;
  errorSummary?: string | null;
  unchanged?: boolean;
  parsedPosts?: number;
  newPosts?: number;
  updatedPosts?: number;
  ignoredPosts?: number;
  productPosts?: number;
  createdCandidates?: number;
  posts?: Array<Record<string, unknown>>;
};

export type ManufacturerSourcesScanResponse = {
  scannedSources: number;
  newPosts: number;
  createdCandidates: number;
  failedSources?: number;
  results: ManufacturerSourceScanResponse[];
};

export type CandidateDecisionResponse = {
  candidateId: string;
  publishedPartId?: string;
  created?: boolean;
  partStatus?: string;
  status: string;
  message?: string;
};

export type CandidateOfferRefreshResponse = {
  configured: boolean;
  candidateId: string;
  updated: boolean;
  attempted: number;
  title?: string;
  lowPrice?: number | null;
  message?: string;
};

export type ManufacturerPostAiAssetDraftResponse = {
  postId: string;
  aiUsed?: boolean;
  classificationStatus: string;
  detectedCategory?: string | null;
  detectedProductName?: string | null;
  confidence?: number | string | null;
  candidateId?: string | null;
  candidateStatus?: string | null;
  partId?: string | null;
  partStatus?: string | null;
  messages?: string[];
};

export type ManufacturerSourcePayload = {
  manufacturer: string;
  categoryScope: string;
  sourceType: string;
  sourceUrl: string;
  enabled: boolean;
  pollIntervalMinutes: number;
  status: string;
  parserConfig?: Record<string, unknown>;
};

export type ManufacturerPostPayload = {
  sourceId: string;
  externalUrl: string;
  title: string;
  publishedAt?: string | null;
  excerpt?: string | null;
  classificationStatus: string;
  detectedCategory?: string | null;
  detectedProductName?: string | null;
  confidence?: number | null;
};

export type CandidatePayload = {
  category?: string;
  searchQuery?: string;
  title?: string;
  manufacturerGuess?: string | null;
  imageUrl?: string | null;
  supplierName?: string | null;
  offerUrl?: string | null;
  lowPrice?: number | null;
};

export type PartAliasResolvePayload = {
  aliasText: string;
  category: string;
  targetField: string;
  canonicalValue: string;
  note?: string;
};

export type AdminPartExternalOffer = {
  title?: string | null;
  imageUrl?: string | null;
  supplierName?: string | null;
  offerUrl?: string | null;
  lowPrice?: number | null;
  source?: string | null;
  refreshedAt?: string | null;
};

export type AdminPart = {
  id: string;
  category: string;
  name: string;
  manufacturer?: string | null;
  price: number;
  status: string;
  attributes: Record<string, unknown>;
  toolReady?: boolean;
  missingRequiredFields?: string[];
  createdAt?: string | null;
  updatedAt?: string | null;
  deletedAt?: string | null;
  externalOffer?: AdminPartExternalOffer | null;
};

export type AdminPartsResponse = {
  items: AdminPart[];
  page: number;
  size: number;
  total: number;
};

export type AdminPartsParams = {
  category?: string;
  q?: string;
  manufacturer?: string;
  status?: string;
  minPrice?: number;
  maxPrice?: number;
  includeDeleted?: boolean;
  page?: number;
  size?: number;
  sort?: string;
};

export type AdminPartPayload = {
  category?: string;
  name?: string;
  manufacturer?: string | null;
  price?: number;
  status?: string;
  attributes?: Record<string, unknown>;
};

export type AdminManualPricePayload = {
  price: number;
  reason?: string;
};

export type AdminExternalOfferPayload = {
  searchQuery?: string | null;
  title?: string | null;
  imageUrl?: string | null;
  supplierName?: string | null;
  offerUrl?: string | null;
  lowPrice?: number | null;
};

export type PartQualityReportCategory = {
  category: string;
  activeParts: number;
  toolReadyMissing: number;
  requiredSpecMissing: number;
  benchmarkMissing: number;
  fpsCoverageGap: number;
  aliasReviewOpen: number;
};

export type PartQualityReportSummary = Omit<PartQualityReportCategory, 'category'>;

export type PartQualityReportActionItem = {
  type: string;
  category?: string | null;
  partId?: string | null;
  id?: string | null;
  label?: string | null;
  message?: string | null;
  targetField?: string | null;
  sourceType?: string | null;
  priority?: string | null;
  createdAt?: string | null;
  [key: string]: unknown;
};

export type PartQualityReportResponse = {
  categories: PartQualityReportCategory[];
  summary: PartQualityReportSummary;
  actionItems: PartQualityReportActionItem[];
  generatedAt?: string;
};

export function getAdminDashboard() {
  return api<AdminDashboard>('/api/admin/dashboard');
}

export function getRecentAdminAuditLogs() {
  return api<AdminAuditLogsResponse>('/api/admin/audit-logs/recent');
}

export function getAdminAgentSessions() {
  return api<AgentSessionsResponse>('/api/admin/agent-sessions');
}

export function getAdminToolInvocations() {
  return api<ToolInvocationsResponse>('/api/admin/tool-invocations');
}

export function getAdminRagEvidence() {
  return api<RagEvidenceResponse>('/api/admin/rag-evidence');
}

export function listAdminParts(params: AdminPartsParams = {}) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, String(value));
    }
  });
  const suffix = query.toString() ? `?${query}` : '';
  return api<AdminPartsResponse>(`/api/admin/parts${suffix}`);
}

export function getAdminPartsQualityReport() {
  return api<PartQualityReportResponse>('/api/admin/parts/quality-report');
}

export function createAdminPart(payload: AdminPartPayload) {
  return api<AdminPart>('/api/admin/parts', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function getAdminPart(partId: string) {
  return api<AdminPart>(`/api/admin/parts/${partId}`);
}

export function updateAdminPart(partId: string, payload: AdminPartPayload) {
  return api<AdminPart>(`/api/admin/parts/${partId}`, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  });
}

export function deleteAdminPart(partId: string) {
  return api<{ id: string; deleted: boolean }>(`/api/admin/parts/${partId}`, {
    method: 'DELETE'
  });
}

export function restoreAdminPart(partId: string) {
  return api<AdminPart>(`/api/admin/parts/${partId}/restore`, {
    method: 'POST'
  });
}

export function updateAdminPartManualPrice(partId: string, payload: AdminManualPricePayload) {
  return api<AdminPart>(`/api/admin/parts/${partId}/manual-price`, {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function updateAdminPartExternalOffer(partId: string, payload: AdminExternalOfferPayload) {
  return api<AdminPart>(`/api/admin/parts/${partId}/external-offer`, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  });
}

export function getAgentSession(sessionId: string) {
  return api<AgentSessionDetail>(`/api/admin/agent-sessions/${sessionId}`);
}

export function getToolInvocation(invocationId: string) {
  return api<ToolInvocation>(`/api/admin/tool-invocations/${invocationId}`);
}

export function getRagEvidence(evidenceId: string) {
  return api<RagEvidenceDetail>(`/api/admin/rag-evidence/${evidenceId}`);
}

export function getAdminTickets() {
  return api<AdminTicketsResponse>('/api/admin/as-tickets');
}

export function getAdminTicket(ticketId: string) {
  return api<AdminAsTicket>(`/api/admin/as-tickets/${ticketId}`);
}

export function listAdminTickets() {
  return getAdminTickets();
}

export function updateAdminTicket(ticketId: string, payload: AdminAsTicketUpdateRequest) {
  return api<AdminAsTicket>(`/api/admin/as-tickets/${ticketId}`, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  });
}

export function listPriceJobs() {
  return api<PriceJobsResponse>('/api/admin/price-jobs');
}

export function runPriceJob() {
  return api<PriceJob>('/api/admin/price-jobs/run', { method: 'POST' });
}

export function listManufacturerSources(includeDeleted = false) {
  const suffix = includeDeleted ? '?includeDeleted=true' : '';
  return api<ManufacturerSourcesResponse>(`/api/admin/manufacturer-sources${suffix}`);
}

export function createManufacturerSource(payload: ManufacturerSourcePayload) {
  return api<ManufacturerSource>('/api/admin/manufacturer-sources', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function updateManufacturerSource(sourceId: string, payload: ManufacturerSourcePayload) {
  return api<ManufacturerSource>(`/api/admin/manufacturer-sources/${sourceId}`, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  });
}

export function deleteManufacturerSource(sourceId: string) {
  return api<{ id: string; deleted: boolean }>(`/api/admin/manufacturer-sources/${sourceId}`, {
    method: 'DELETE'
  });
}

export function restoreManufacturerSource(sourceId: string) {
  return api<ManufacturerSource>(`/api/admin/manufacturer-sources/${sourceId}/restore`, {
    method: 'POST'
  });
}

export function scanManufacturerSource(sourceId: string) {
  return api<ManufacturerSourceScanResponse>(`/api/admin/manufacturer-sources/${sourceId}/scan?limit=20&createCandidates=true`, {
    method: 'POST'
  });
}

export function scanAllManufacturerSources() {
  return api<ManufacturerSourcesScanResponse>('/api/admin/manufacturer-sources/scan?limitPerSource=20&createCandidates=true', {
    method: 'POST'
  });
}

export function listManufacturerPosts() {
  return api<ManufacturerPostsResponse>('/api/admin/manufacturer-posts?page=0&size=10&includeDeleted=true');
}

export function createManufacturerPost(payload: ManufacturerPostPayload) {
  return api<ManufacturerPost>('/api/admin/manufacturer-posts', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function updateManufacturerPost(postId: string, payload: ManufacturerPostPayload) {
  return api<ManufacturerPost>(`/api/admin/manufacturer-posts/${postId}`, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  });
}

export function deleteManufacturerPost(postId: string) {
  return api<{ id: string; deleted: boolean }>(`/api/admin/manufacturer-posts/${postId}`, {
    method: 'DELETE'
  });
}

export function restoreManufacturerPost(postId: string) {
  return api<ManufacturerPost>(`/api/admin/manufacturer-posts/${postId}/restore`, {
    method: 'POST'
  });
}

export function createCandidateFromManufacturerPost(postId: string) {
  return api<{ configured?: boolean; created?: boolean; candidateId?: string; title?: string; lowPrice?: number | null; message?: string }>(`/api/admin/manufacturer-posts/${postId}/create-candidate`, {
    method: 'POST'
  });
}

export function createAiAssetDraftFromManufacturerPost(postId: string) {
  return api<ManufacturerPostAiAssetDraftResponse>(`/api/admin/manufacturer-posts/${postId}/ai-asset-draft`, {
    method: 'POST'
  });
}

export function listManufacturerReleaseCandidates() {
  return api<PartCatalogCandidatesResponse>('/api/admin/part-catalog-candidates?source=MANUFACTURER_RELEASE_NAVER_SEARCH&page=0&size=10&includeDeleted=true');
}

export function updatePartCatalogCandidate(candidateId: string, payload: CandidatePayload) {
  return api<PartCatalogCandidate>(`/api/admin/part-catalog-candidates/${candidateId}`, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  });
}

export function deletePartCatalogCandidate(candidateId: string) {
  return api<{ id: string; deleted: boolean }>(`/api/admin/part-catalog-candidates/${candidateId}`, {
    method: 'DELETE'
  });
}

export function restorePartCatalogCandidate(candidateId: string) {
  return api<PartCatalogCandidate>(`/api/admin/part-catalog-candidates/${candidateId}/restore`, {
    method: 'POST'
  });
}

export function approvePartCatalogCandidate(candidateId: string) {
  return api<CandidateDecisionResponse>(`/api/admin/part-catalog-candidates/${candidateId}/approve`, {
    method: 'POST'
  });
}

export function rejectPartCatalogCandidate(candidateId: string) {
  return api<CandidateDecisionResponse>(`/api/admin/part-catalog-candidates/${candidateId}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reason: '관리자 데모 후보 검토에서 제외' })
  });
}

export function refreshPartCatalogCandidateOffers(candidateId: string) {
  return api<CandidateOfferRefreshResponse>(`/api/admin/part-catalog-candidates/${candidateId}/refresh-offers`, {
    method: 'POST'
  });
}

export function listPartAliasReviewItems(params: PartAliasReviewItemsParams = {}) {
  const query = new URLSearchParams();
  Object.entries({ status: 'OPEN', page: 0, size: 20, ...params }).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, String(value));
    }
  });
  return api<PartAliasReviewItemsResponse>(`/api/admin/part-alias-review-items?${query}`);
}

export function getPartAliasReviewSummary() {
  return api<PartAliasReviewSummaryResponse>('/api/admin/part-alias-review-items/summary');
}

export function resolvePartAliasReviewItem(itemId: string, payload: PartAliasResolvePayload) {
  return api<PartAliasReviewItem>(`/api/admin/part-alias-review-items/${itemId}/resolve`, {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function ignorePartAliasReviewItem(itemId: string, note?: string) {
  return api<PartAliasReviewItem>(`/api/admin/part-alias-review-items/${itemId}/ignore`, {
    method: 'POST',
    body: JSON.stringify({ note })
  });
}

export function listPartAliasRules() {
  return api<PartAliasRulesResponse>('/api/admin/part-alias-rules?page=0&size=50');
}

export function createPartAliasRule(payload: PartAliasResolvePayload) {
  return api<PartAliasRule>('/api/admin/part-alias-rules', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}
