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

export type RagEvidenceDetail = {
  id: string;
  agentSessionId?: string;
  sourceId: string;
  chunkText?: string;
  summary: string;
  score?: string | number | null;
  metadata?: Record<string, unknown>;
};

export function getAdminDashboard() {
  return api<AdminDashboard>('/api/admin/dashboard');
}

export function getRecentAdminAuditLogs() {
  return api<AdminAuditLogsResponse>('/api/admin/audit-logs/recent');
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

export function getAdminTicket(ticketId: string) {
  return api(`/api/admin/as-tickets/${ticketId}`);
}

export function runPriceJob() {
  return api('/api/admin/price-jobs/run', { method: 'POST' });
}
