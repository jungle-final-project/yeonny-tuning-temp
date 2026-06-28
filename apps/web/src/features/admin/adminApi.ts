import { api } from '../../lib/api';

export type AdminDashboard = {
  llmQueueP95: string;
  apiP95: string;
  asOpen: number;
  recommendationSuccess: string;
};

export function getAdminDashboard() {
  return api<AdminDashboard>('/api/admin/dashboard');
}

export function getAgentSession(sessionId: string) {
  return api(`/api/admin/agent-sessions/${sessionId}`);
}

export function getToolInvocation(invocationId: string) {
  return api(`/api/admin/tool-invocations/${invocationId}`);
}

export function getRagEvidence(evidenceId: string) {
  return api(`/api/admin/rag-evidence/${evidenceId}`);
}

export function getAdminTicket(ticketId: string) {
  return api(`/api/admin/as-tickets/${ticketId}`);
}

export function runPriceJob() {
  return api('/api/admin/price-jobs/run', { method: 'POST' });
}
