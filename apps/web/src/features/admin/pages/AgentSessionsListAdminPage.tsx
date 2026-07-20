import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage } from '../../../components/ui';
import { formatKstDateTime } from '../../../lib/dateTime';
import { getAdminAgentSessions } from '../adminApi';
import { KoreanStatusBadge } from '../adminDisplay';

export function AgentSessionsListAdminPage() {
  const { data, isError, isLoading } = useQuery({
    queryKey: ['admin-agent-sessions'],
    queryFn: getAdminAgentSessions
  });
  const sessions = data?.items ?? [];
  const rows = sessions.map((session) => ({
    식별자: <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${session.id}`}>{session.id}</Link>,
    상태: <KoreanStatusBadge status={session.status} />,
    사용자: session.userId ?? '-',
    '생성 시간': formatKstDateTime(session.createdAt)
  }));
  const exportRows = sessions.map((session) => ({
    id: session.id,
    status: session.status,
    userId: session.userId ?? '',
    createdAt: formatKstDateTime(session.createdAt)
  }));

  return (
    <AdminShell title="에이전트 세션 목록" exportRows={exportRows} exportFileName="admin-agent-sessions.csv">
      <Panel title="에이전트 세션 목록" subtitle="최근 에이전트 실행 이력">
        {isLoading ? (
          <StateMessage type="info" title="에이전트 세션 로딩 중" body="관리자 에이전트 세션 목록을 불러오고 있습니다." />
        ) : isError ? (
          <StateMessage type="warn" title="에이전트 세션 조회 실패" body="관리자 에이전트 세션 목록 응답을 불러오지 못했습니다." />
        ) : rows.length ? (
          <DataTable columns={['식별자', '상태', '사용자', '생성 시간']} rows={rows} />
        ) : (
          <StateMessage type="info" title="에이전트 세션 없음" body="표시할 에이전트 세션이 없습니다." />
        )}
      </Panel>
    </AdminShell>
  );
}
