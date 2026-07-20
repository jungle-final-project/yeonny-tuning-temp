import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage } from '../../../components/ui';
import { formatKstDateTime } from '../../../lib/dateTime';
import { getToolInvocation } from '../adminApi';
import { KoreanStatusBadge, koreanStatusLabel, koreanToolLabel } from '../adminDisplay';
import type { ToolInvocation } from '../adminApi';

export function ToolInvocationAdminPage() {
  const { id } = useParams();
  const {
    data: invocation,
    isError,
    isLoading
  } = useQuery({
    queryKey: ['admin-tool-invocation', id],
    queryFn: () => getToolInvocation(id ?? ''),
    enabled: Boolean(id)
  });

  if (!id) {
    return (
      <AdminShell title="도구 호출 상세">
        <StateMessage type="info" title="도구 호출을 선택하세요" body="에이전트 세션 상세에서 도구 호출 항목을 선택해야 합니다." />
      </AdminShell>
    );
  }

  if (isLoading) {
    return (
      <AdminShell title="도구 호출 상세">
        <StateMessage type="info" title="도구 호출 로딩 중" body="도구 요청과 결과 데이터를 불러오고 있습니다." />
      </AdminShell>
    );
  }

  if (isError || !invocation) {
    return (
      <AdminShell title="도구 호출 상세">
        <StateMessage type="warn" title="도구 호출 조회 실패" body="관리자 도구 호출 상세 응답을 불러오지 못했습니다." />
      </AdminShell>
    );
  }

  return (
    <AdminShell title="도구 호출 상세">
      <div className="grid grid-cols-[1fr_420px] gap-5">
        <Panel title="호출 상세" subtitle={`${koreanToolLabel(invocation.toolName)} / ${invocation.id}`}>
          <DataTable columns={['필드', '값']} rows={detailRows(invocation)} />
        </Panel>
        <Panel title="결과 요약">
          <StateMessage type={invocation.status === 'PASS' ? 'success' : 'warn'} title={`${koreanStatusLabel(invocation.status)} / ${koreanStatusLabel(invocation.confidence)}`} body={invocation.summary} />
        </Panel>
        <Panel title="요청 데이터" className="col-span-1">
          <JsonBlock value={invocation.requestPayload ?? {}} />
        </Panel>
        <Panel title="결과 데이터">
          <JsonBlock value={invocation.resultPayload ?? {}} />
        </Panel>
      </div>
    </AdminShell>
  );
}

function detailRows(invocation: ToolInvocation) {
  return [
    { 필드: '호출 식별자', 값: invocation.id },
    { 필드: '도구', 값: koreanToolLabel(invocation.toolName) },
    { 필드: '상태', 값: <KoreanStatusBadge status={invocation.status} /> },
    { 필드: '근거 수준', 값: <KoreanStatusBadge status={invocation.confidence} /> },
    { 필드: '지연 시간', 값: invocation.latencyMs == null ? '-' : `${invocation.latencyMs}ms` },
    {
      필드: '세션 식별자',
      값: <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${invocation.agentSessionId}`}>{invocation.agentSessionId}</Link>
    },
    { 필드: '생성 시간', 값: formatKstDateTime(invocation.createdAt) }
  ];
}

function JsonBlock({ value }: { value: unknown }) {
  return (
    <pre className="max-h-[420px] overflow-auto rounded bg-slate-950 p-5 font-mono text-xs leading-6 text-slate-200">
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}
