import { useQueries, useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage } from '../../../components/ui';
import { getAgentSession, getRagEvidence } from '../adminApi';
import { KoreanStatusBadge, koreanStatusLabel, koreanToolLabel } from '../adminDisplay';
import type { AgentSessionDetail, LlmGeneration, RagEvidenceDetail, ToolInvocation } from '../adminApi';

export function AgentSessionAdminPage() {
  const { id } = useParams();
  const {
    data: session,
    isError,
    isLoading
  } = useQuery({
    queryKey: ['admin-agent-session', id],
    queryFn: () => getAgentSession(id ?? ''),
    enabled: Boolean(id),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === 'QUEUED' || status === 'RUNNING' || status === 'RAG_SEARCHED' || status === 'TOOLS_CALLED' || status === 'SUMMARY_READY' ? 2000 : false;
    }
  });

  const evidenceQueries = useQueries({
    queries: (session?.evidenceIds ?? []).map((evidenceId) => ({
      queryKey: ['admin-rag-evidence', evidenceId],
      queryFn: () => getRagEvidence(evidenceId)
    }))
  });

  if (!id) {
    return (
      <AdminShell title="에이전트 / 검색 근거 / 도구 근거 상세">
        <StateMessage type="info" title="에이전트 세션을 선택하세요" body="운영 대시보드의 에이전트 세션 목록에서 상세 항목을 선택해야 합니다." />
      </AdminShell>
    );
  }

  if (isLoading) {
    return (
      <AdminShell title="에이전트 / 검색 근거 / 도구 근거 상세">
        <StateMessage type="info" title="에이전트 세션 로딩 중" body="에이전트 실행 이력과 근거 데이터를 불러오고 있습니다." />
      </AdminShell>
    );
  }

  if (isError || !session) {
    return (
      <AdminShell title="에이전트 / 검색 근거 / 도구 근거 상세">
        <StateMessage type="warn" title="에이전트 세션 조회 실패" body="관리자 에이전트 세션 상세 응답을 불러오지 못했습니다." />
      </AdminShell>
    );
  }

  return (
    <AdminShell title="에이전트 / 검색 근거 / 도구 근거 상세">
      <div className="grid grid-cols-[1fr_520px] gap-5">
        <Panel title="에이전트 실행 이력" subtitle={`${session.purpose ?? '목적 없음'} / ${session.id}`}>
          {session.stateTimeline.length ? (
            <DataTable columns={['순서', '전이', '상태', '실행 주체', '시각', '사유']} rows={timelineRows(session)} />
          ) : (
            <StateMessage type="info" title="상태 전이 없음" body="아직 에이전트 상태 전이 기록이 없습니다." />
          )}
        </Panel>
        <Panel title="실행 요약">
          <StateMessage type={session.status === 'SUCCEEDED' ? 'success' : 'info'} title={`현재 상태: ${koreanStatusLabel(session.status)}`} body={session.summary ?? '아직 에이전트 요약이 생성되지 않았습니다.'} />
          <div className="mt-4 rounded bg-slate-950 p-5 font-mono text-xs leading-6 text-slate-200">
            대기 -&gt; 실행 중 -&gt; 근거 검색 완료 -&gt; 도구 호출 완료 -&gt; 요약 준비 -&gt; 성공<br />
            대체 흐름: 대체 응답 준비 -&gt; 성공
          </div>
        </Panel>
        <Panel title="도구 호출 이력">
          {session.toolInvocations.length ? (
            <DataTable columns={['식별자', '도구', '상태', '근거 수준', '지연 시간', '요약']} rows={toolRows(session.toolInvocations)} />
          ) : (
            <StateMessage type="info" title="도구 호출 없음" body="이 에이전트 세션에 연결된 도구 호출 기록이 없습니다." />
          )}
        </Panel>
        <Panel title="언어모델 생성 기록">
          {session.llmGenerations?.length ? (
            <DataTable columns={['식별자', '프로필', '모델', '상태', '스키마', '지연 시간', '토큰']} rows={llmRows(session.llmGenerations)} />
          ) : (
            <StateMessage type="info" title="언어모델 호출 없음" body="이 에이전트 세션에 연결된 언어모델 생성 기록이 없습니다." />
          )}
        </Panel>
        <Panel title="검색 근거">
          {session.evidenceIds.length ? (
            <DataTable columns={['식별자', '출처 식별자', '요약', '점수', '메타데이터']} rows={evidenceRows(session.evidenceIds, evidenceQueries.map((query) => query.data))} />
          ) : (
            <StateMessage type="info" title="검색 근거 없음" body="이 에이전트 세션에 연결된 검색 근거가 없습니다." />
          )}
        </Panel>
      </div>
    </AdminShell>
  );
}

function timelineRows(session: AgentSessionDetail) {
  return session.stateTimeline.map((item, index) => ({
    순서: index + 1,
    전이: `${koreanStatusLabel(item.from)} -> ${koreanStatusLabel(item.to)}`,
    상태: <KoreanStatusBadge status={item.to} />,
    '실행 주체': item.actor,
    시각: formatDateTime(item.at),
    사유: item.reason ?? '-'
  }));
}

function toolRows(toolInvocations: ToolInvocation[]) {
  return toolInvocations.map((invocation) => ({
    식별자: <Link className="font-bold text-brand-blue" to={`/admin/tool-invocations/${invocation.id}`}>{shortId(invocation.id)}</Link>,
    도구: koreanToolLabel(invocation.toolName),
    상태: <KoreanStatusBadge status={invocation.status} />,
    '근거 수준': <KoreanStatusBadge status={invocation.confidence} />,
    '지연 시간': invocation.latencyMs == null ? '-' : `${invocation.latencyMs}ms`,
    요약: invocation.summary
  }));
}

function llmRows(generations: LlmGeneration[]) {
  return generations.map((generation) => ({
    식별자: shortId(generation.id),
    프로필: generation.aiProfile,
    모델: `${generation.model}${generation.reasoningEffort ? ` / ${generation.reasoningEffort}` : ''}`,
    상태: <KoreanStatusBadge status={generation.status} />,
    스키마: generation.schemaValid ? '유효' : generation.errorCode ?? '유효하지 않음',
    '지연 시간': generation.latencyMs == null ? '-' : `${generation.latencyMs}ms`,
    토큰: generation.totalTokens == null ? '-' : String(generation.totalTokens)
  }));
}

function evidenceRows(evidenceIds: string[], evidenceItems: Array<RagEvidenceDetail | undefined>) {
  return evidenceIds.map((evidenceId, index) => {
    const evidence = evidenceItems[index];
    return {
      식별자: <Link className="font-bold text-brand-blue" to={`/admin/rag-evidence/${evidenceId}`}>{shortId(evidenceId)}</Link>,
      '출처 식별자': evidence?.sourceId ?? '조회 중',
      요약: evidence?.summary ?? '근거 상세를 불러오고 있습니다.',
      점수: formatScore(evidence?.score),
      메타데이터: formatMetadata(evidence?.metadata)
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
  return sourceType == null ? '메타데이터' : String(sourceType);
}
