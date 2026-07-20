import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage } from '../../../components/ui';
import { formatKstDateTime } from '../../../lib/dateTime';
import { getAdminToolInvocations } from '../adminApi';
import { KoreanStatusBadge, koreanToolLabel } from '../adminDisplay';

export function ToolInvocationsListAdminPage() {
  const { data, isError, isLoading } = useQuery({
    queryKey: ['admin-tool-invocations'],
    queryFn: getAdminToolInvocations
  });
  const invocations = data?.items ?? [];
  const rows = invocations.map((invocation) => ({
    식별자: <Link className="font-bold text-brand-blue" to={`/admin/tool-invocations/${invocation.id}`}>{invocation.id}</Link>,
    세션: invocation.agentSessionId
      ? <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${invocation.agentSessionId}`}>{invocation.agentSessionId}</Link>
      : '-',
    도구: koreanToolLabel(invocation.toolName),
    상태: <KoreanStatusBadge status={invocation.status} />,
    '근거 수준': <KoreanStatusBadge status={invocation.confidence} />,
    '생성 시간': formatKstDateTime(invocation.createdAt)
  }));
  const exportRows = invocations.map((invocation) => ({
    id: invocation.id,
    agentSessionId: invocation.agentSessionId,
    toolName: invocation.toolName,
    status: invocation.status,
    confidence: invocation.confidence,
    createdAt: formatKstDateTime(invocation.createdAt)
  }));

  return (
    <AdminShell title="도구 호출 목록" exportRows={exportRows} exportFileName="admin-tool-invocations.csv">
      <Panel title="도구 호출 목록" subtitle="검증 도구 실행 이력">
        {isLoading ? (
          <StateMessage type="info" title="도구 호출 로딩 중" body="관리자 도구 호출 목록을 불러오고 있습니다." />
        ) : isError ? (
          <StateMessage type="warn" title="도구 호출 조회 실패" body="관리자 도구 호출 목록 응답을 불러오지 못했습니다." />
        ) : rows.length ? (
          <DataTable columns={['식별자', '세션', '도구', '상태', '근거 수준', '생성 시간']} rows={rows} />
        ) : (
          <StateMessage type="info" title="도구 호출 없음" body="표시할 도구 호출 기록이 없습니다." />
        )}
      </Panel>
    </AdminShell>
  );
}
