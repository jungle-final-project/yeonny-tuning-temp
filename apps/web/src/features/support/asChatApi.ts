import { API_BASE_URL, ApiError, api, getToken } from '../../lib/api';

export const AS_CHAT_DEFAULT_TICKET_ID = '00000000-0000-4000-8000-000000006001';

export type AsChatTicket = {
  id: string;
  status: string;
  symptom: string;
  logSummary?: string;
  causeCandidates?: unknown[];
  upgradeCandidates?: unknown[];
  createdAt?: string;
};

export type AsChatMessage = {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  structuredPayload?: Record<string, unknown>;
  agentSessionId?: string | null;
  createdAt?: string;
};

export type AsChatCauseCandidate = {
  label?: string;
  confidence?: string;
  reason?: string;
  evidenceIds?: string[];
  toolInvocationIds?: string[];
};

export type AsChatNextAction = {
  label?: string;
  priority?: string;
  instruction?: string;
  evidenceIds?: string[];
  toolInvocationIds?: string[];
};

export type AsChatEvidence = {
  id: string;
  sourceId: string;
  summary: string;
  chunkText?: string;
  score?: number | string;
  metadata?: Record<string, unknown>;
};

export type AsChatToolResult = {
  id: string;
  toolName: string;
  status: string;
  confidence: string;
  summary: string;
  resultPayload?: Record<string, unknown>;
};

export type AsChatResponse = {
  sessionId?: string | null;
  asTicketId: string;
  ticket: AsChatTicket;
  model: string;
  agentSessionId?: string;
  messages: AsChatMessage[];
  assistantMessage?: string;
  causeCandidates?: AsChatCauseCandidate[];
  nextActions?: AsChatNextAction[];
  escalation?: { required?: boolean; reason?: string };
  ticketDraft?: { symptomSummary?: string; recommendedLogRequest?: string };
  evidence: AsChatEvidence[];
  toolResults: AsChatToolResult[];
};

export type AsChatProgressEvent = {
  event: 'STARTED' | 'RAG_READY' | 'TOOLS_READY' | 'LLM_RUNNING' | 'DONE' | 'ERROR' | string;
  data: Record<string, unknown>;
};

export function getAsChat(asTicketId: string) {
  return api<AsChatResponse>(`/api/ai/as-chat?asTicketId=${encodeURIComponent(asTicketId)}`);
}

export function sendAsChat(asTicketId: string, message: string) {
  return api<AsChatResponse>('/api/ai/as-chat', {
    method: 'POST',
    body: JSON.stringify({ asTicketId, message })
  });
}

export async function streamAsChat(
  asTicketId: string,
  message: string,
  onEvent: (event: AsChatProgressEvent) => void
) {
  const token = getToken();
  const response = await fetch(`${API_BASE_URL}/api/ai/as-chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify({ asTicketId, message })
  });

  if (!response.ok) {
    throw new ApiError(response.status, '/api/ai/as-chat/stream');
  }
  if (!response.body) {
    throw new Error('AS Chat stream body is empty');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finalResponse: AsChatResponse | null = null;

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split(/\r?\n\r?\n/);
    buffer = events.pop() ?? '';
    for (const rawEvent of events) {
      const parsed = parseSseEvent(rawEvent);
      if (!parsed) continue;
      onEvent(parsed);
      if (parsed.event === 'DONE') {
        finalResponse = parsed.data as AsChatResponse;
      }
      if (parsed.event === 'ERROR') {
        throw new Error(String(parsed.data.message ?? 'AS Chat stream failed'));
      }
    }
  }

  if (!finalResponse) {
    throw new Error('AS Chat stream completed without DONE event');
  }
  return finalResponse;
}

function parseSseEvent(rawEvent: string): AsChatProgressEvent | null {
  const lines = rawEvent.split(/\r?\n/);
  let event = 'message';
  const dataLines: string[] = [];
  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim();
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart());
    }
  }
  if (!dataLines.length) {
    return null;
  }
  return {
    event,
    data: JSON.parse(dataLines.join('\n')) as Record<string, unknown>
  };
}
