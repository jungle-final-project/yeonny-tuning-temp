import { useQueries, useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { getAgentSession, getRagEvidence } from '../adminApi';
import type { AgentSessionDetail, RagEvidenceDetail, ToolInvocation } from '../adminApi';

export function AgentSessionAdminPage() {
  const { id = '00000000-0000-4000-8000-000000003001' } = useParams();
  const {
    data: session,
    isError,
    isLoading
  } = useQuery({
    queryKey: ['admin-agent-session', id],
    queryFn: () => getAgentSession(id)
  });

  const evidenceQueries = useQueries({
    queries: (session?.evidenceIds ?? []).map((evidenceId) => ({
      queryKey: ['admin-rag-evidence', evidenceId],
      queryFn: () => getRagEvidence(evidenceId)
    }))
  });

  if (isLoading) {
    return (
      <AdminShell title="Agent / RAG / Tool 근거 상세">
        <StateMessage type="info" title="Agent 세션 로딩 중" body="Agent 실행 trace와 근거 데이터를 불러오고 있습니다." />
      </AdminShell>
    );
  }

  if (isError || !session) {
    return (
      <AdminShell title="Agent / RAG / Tool 근거 상세">
        <StateMessage type="warn" title="Agent 세션 조회 실패" body="관리자 Agent 세션 상세 API 응답을 불러오지 못했습니다." />
      </AdminShell>
    );
  }

  return (
    <AdminShell title="Agent / RAG / Tool 근거 상세">
      <div className="grid grid-cols-[1fr_520px] gap-5">
        <Panel title="Agent 실행 Trace" subtitle={`${session.purpose ?? 'UNKNOWN'} / ${session.id}`}>
          {session.stateTimeline.length ? (
            <DataTable columns={['step', 'transition', 'status', 'actor', 'at', 'reason']} rows={timelineRows(session)} />
          ) : (
            <StateMessage type="info" title="상태 전이 없음" body="아직 Agent 상태 전이 기록이 없습니다." />
          )}
        </Panel>
        <Panel title="실행 요약">
          <StateMessage type={session.status === 'SUCCEEDED' ? 'success' : 'info'} title={`현재 상태: ${session.status}`} body={session.summary ?? '아직 Agent summary가 생성되지 않았습니다.'} />
          <div className="mt-4 rounded bg-slate-950 p-5 font-mono text-xs leading-6 text-slate-200">
            QUEUED -&gt; RUNNING -&gt; RAG_SEARCHED -&gt; TOOLS_CALLED -&gt; SUMMARY_READY -&gt; SUCCEEDED<br />
            fallback: FALLBACK_READY -&gt; SUCCEEDED
          </div>
        </Panel>
        <Panel title="Tool 호출 이력">
          {session.toolInvocations.length ? (
            <DataTable columns={['id', 'tool', 'status', 'confidence', 'latency', 'summary']} rows={toolRows(session.toolInvocations)} />
          ) : (
            <StateMessage type="info" title="Tool 호출 없음" body="이 Agent 세션에 연결된 Tool 호출 기록이 없습니다." />
          )}
        </Panel>
        <Panel title="RAG Evidence">
          {session.evidenceIds.length ? (
            <DataTable columns={['id', 'sourceId', 'summary', 'score', 'metadata']} rows={evidenceRows(session.evidenceIds, evidenceQueries.map((query) => query.data))} />
          ) : (
            <StateMessage type="info" title="RAG 근거 없음" body="이 Agent 세션에 연결된 RAG evidence가 없습니다." />
          )}
        </Panel>
      </div>
    </AdminShell>
  );
}

function timelineRows(session: AgentSessionDetail) {
  return session.stateTimeline.map((item, index) => ({
    step: index + 1,
    transition: `${item.from ?? '-'} -> ${item.to}`,
    status: <StatusBadge status={item.to} />,
    actor: item.actor,
    at: formatDateTime(item.at),
    reason: item.reason ?? '-'
  }));
}

function toolRows(toolInvocations: ToolInvocation[]) {
  return toolInvocations.map((invocation) => ({
    id: <Link className="font-bold text-brand-blue" to={`/admin/tool-invocations/${invocation.id}`}>{shortId(invocation.id)}</Link>,
    tool: invocation.toolName,
    status: <StatusBadge status={invocation.status} />,
    confidence: <StatusBadge status={invocation.confidence} />,
    latency: invocation.latencyMs == null ? '-' : `${invocation.latencyMs}ms`,
    summary: invocation.summary
  }));
}

function evidenceRows(evidenceIds: string[], evidenceItems: Array<RagEvidenceDetail | undefined>) {
  return evidenceIds.map((evidenceId, index) => {
    const evidence = evidenceItems[index];
    return {
      id: <Link className="font-bold text-brand-blue" to={`/admin/rag-evidence/${evidenceId}`}>{shortId(evidenceId)}</Link>,
      sourceId: evidence?.sourceId ?? '조회 중',
      summary: evidence?.summary ?? '근거 상세를 불러오고 있습니다.',
      score: formatScore(evidence?.score),
      metadata: formatMetadata(evidence?.metadata)
    };
  });
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function formatDateTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

function formatScore(value?: string | number | null) {
  return value == null ? '-' : String(value);
}

function formatMetadata(metadata?: Record<string, unknown>) {
  if (!metadata || Object.keys(metadata).length === 0) {
    return '-';
  }
  const sourceType = metadata.sourceType ?? metadata.sourceTypes;
  if (Array.isArray(sourceType)) {
    return sourceType.join(', ');
  }
  return sourceType == null ? 'metadata' : String(sourceType);
}
