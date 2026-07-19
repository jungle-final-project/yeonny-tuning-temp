import { api } from '../../lib/api';
import type { AgentActivationTokenDto, AgentLogUploadDto, AsRagAnalysisDto, AsTicketDraftDto, AsTicketDto, RemoteSupportStateDto } from './types';

export type UploadAgentLogMetadata = {
  rangeStartedAt?: string;
  rangeEndedAt?: string;
  incidentId?: string;
  triggerType?: string;
  symptomType?: string;
  detectedAt?: string;
  selectedByUser?: boolean;
  consentId?: string;
};

export type RemoteSupportRequestCreateRequest = {
  reason: string;
  contactPhone?: string;
};

export type SupportFeedbackRequest = {
  rating: number;
  comment?: string;
};

export type PcAgentDiagnosisRequestCreate = {
  symptom: string;
  requestedChecks: Array<'cpu' | 'gpu' | 'memory' | 'disk' | 'cooling'>;
  mode: 'LIVE' | 'DEMO';
};

export type PcAgentDiagnosisRequestDto = {
  diagnosisId: string;
  deviceId: string;
  requestedAt: string;
  expiresAt: string;
  mode: 'LIVE' | 'DEMO';
  status: 'ACCEPTED' | 'DUPLICATE' | 'EXPIRED' | 'DEVICE_MISMATCH' | 'AUTH_FAILED' | 'BUSY' | 'REJECTED';
  message?: string;
};

export type PcAgentDiagnosisEventDto = {
  eventId: string;
  taskId?: string | null;
  eventType: string;
  status: string;
  progressPercent: number;
  message?: string | null;
  occurredAt: string;
  rawPayload?: unknown;
  createdAt: string;
};

export type PcAgentDiagnosisResultDto = {
  resultId: string;
  diagnosisType?: string | null;
  severity: string;
  title: string;
  summary: string;
  resolutionType: string;
  canAutoRecover: boolean;
  evidence: unknown[];
  findings: unknown[];
  actions: unknown[];
  dataMode: 'LIVE' | 'DEMO';
  scenarioId?: string | null;
  rawPayload?: unknown;
  createdAt: string;
  updatedAt: string;
};

export type PcAgentDiagnosisTicketDto = {
  id: string;
  status: string;
  reviewStatus?: string | null;
  supportDecision?: string | null;
  createdAt?: string | null;
};

export type PcAgentDiagnosisRecentMessageDto = {
  eventId: string;
  status: string;
  progressPercent: number;
  message: string;
  occurredAt: string;
};

export type PcAgentDiagnosisSummaryDto = {
  diagnosisId: string;
  status: string;
  connectionStatus?: string | null;
  agentConnected: boolean;
  accepted: boolean;
  currentStatus?: string | null;
  currentProgress: number;
  currentTask?: string | null;
  recentMessages: PcAgentDiagnosisRecentMessageDto[];
  completed: boolean;
  resultAvailable: boolean;
  resultSeverity?: string | null;
  resolutionType?: string | null;
  dataMode?: 'LIVE' | 'DEMO' | null;
  scenarioId?: string | null;
  requestedAt?: string | null;
  completedAt?: string | null;
  asTicket?: PcAgentDiagnosisTicketDto | null;
  createdAt: string;
  updatedAt: string;
};

export type PcAgentDiagnosisDto = {
  diagnosisId: string;
  status: string;
  connectionStatus?: string | null;
  agentConnected: boolean;
  accepted: boolean;
  currentProgress: number;
  currentTask?: string | null;
  events: PcAgentDiagnosisEventDto[];
  completed: boolean;
  result?: PcAgentDiagnosisResultDto | null;
  resolutionType?: string | null;
  dataMode?: 'LIVE' | 'DEMO' | null;
  scenarioId?: string | null;
  requestedAt?: string | null;
  completedAt?: string | null;
  asTicket?: PcAgentDiagnosisTicketDto | null;
  createdAt: string;
  updatedAt: string;
};

export type LatestPcAgentDiagnosisDto = {
  diagnosis: PcAgentDiagnosisSummaryDto | null;
};

export function uploadAgentLog(rangeMinutes: number, consentAccepted: boolean, file: File, metadata: UploadAgentLogMetadata = {}) {
  const body = new FormData();
  body.append('file', file);
  body.append('rangeMinutes', String(rangeMinutes));
  body.append('consentAccepted', String(consentAccepted));
  Object.entries(metadata).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      body.append(key, String(value));
    }
  });

  return api<AgentLogUploadDto>('/api/agent-logs/upload', {
    method: 'POST',
    body
  });
}

export function issueAgentActivationToken() {
  return api<AgentActivationTokenDto>('/api/users/me/agent-activation-token', {
    method: 'POST',
    body: JSON.stringify({})
  });
}

export function requestPcAgentDiagnosis(request: PcAgentDiagnosisRequestCreate) {
  return api<PcAgentDiagnosisRequestDto>('/api/users/me/agent-diagnosis-requests', {
    method: 'POST',
    body: JSON.stringify(request)
  });
}

export function getPcAgentDiagnosis(diagnosisId: string, signal?: AbortSignal) {
  return api<PcAgentDiagnosisDto>(`/api/users/me/agent-diagnosis-requests/${encodeURIComponent(diagnosisId)}`, {
    signal
  });
}

export function getLatestPcAgentDiagnosis(signal?: AbortSignal) {
  return api<LatestPcAgentDiagnosisDto>('/api/users/me/agent-diagnosis-requests/latest', {
    signal
  });
}

export function previewAgentLogRag(rangeMinutes: number, file: File) {
  const body = new FormData();
  body.append('file', file);
  body.append('rangeMinutes', String(rangeMinutes));

  return api<AsRagAnalysisDto>('/api/agent-logs/as-rag-preview', {
    method: 'POST',
    body
  });
}

export function createSupportTicket(symptom: string, logUploadId: string) {
  return api<AsTicketDto>('/api/as-tickets', {
    method: 'POST',
    body: JSON.stringify({ symptom, logUploadId })
  });
}

export function getSupportDraft(draftId: string) {
  return api<AsTicketDraftDto>(`/api/as-ticket-drafts/${draftId}`);
}

export function getSupportTicket(ticketId: string) {
  return api<AsTicketDto>(`/api/as-tickets/${ticketId}`);
}

export function requestRemoteSupport(ticketId: string, request: RemoteSupportRequestCreateRequest) {
  return api<AsTicketDto>(`/api/as-tickets/${ticketId}/remote-support-requests`, {
    method: 'POST',
    body: JSON.stringify(request)
  });
}

export function getRemoteSupportState(ticketId: string) {
  return api<RemoteSupportStateDto>(`/api/as-tickets/${ticketId}/remote-support`);
}

export function registerRemoteSupportAccessCode(ticketId: string, accessCode: string) {
  return api<RemoteSupportStateDto>(`/api/as-tickets/${ticketId}/remote-support/access-code`, {
    method: 'PUT',
    body: JSON.stringify({ accessCode })
  });
}

export function submitSupportFeedback(ticketId: string, request: SupportFeedbackRequest) {
  return api<AsTicketDto>(`/api/as-tickets/${ticketId}/feedback`, {
    method: 'POST',
    body: JSON.stringify(request)
  });
}
