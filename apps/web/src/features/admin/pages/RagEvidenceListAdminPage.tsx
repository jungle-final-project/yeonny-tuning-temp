import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage } from '../../../components/ui';
import { getAdminRagEvidence } from '../adminApi';

export function RagEvidenceListAdminPage() {
  const { data, isError, isLoading } = useQuery({
    queryKey: ['admin-rag-evidence'],
    queryFn: getAdminRagEvidence
  });
  const evidenceItems = data?.items ?? [];
  const rows = evidenceItems.map((evidence) => ({
    식별자: <Link className="font-bold text-brand-blue" to={`/admin/rag-evidence/${evidence.id}`}>{evidence.id}</Link>,
    세션: evidence.agentSessionId
      ? <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${evidence.agentSessionId}`}>{evidence.agentSessionId}</Link>
      : '-',
    '출처 식별자': evidence.sourceId,
    요약: evidence.summary,
    점수: formatScore(evidence.score)
  }));
  const exportRows = evidenceItems.map((evidence) => ({
    id: evidence.id,
    agentSessionId: evidence.agentSessionId ?? '',
    sourceId: evidence.sourceId,
    summary: evidence.summary,
    score: formatScore(evidence.score)
  }));

  return (
    <AdminShell title="검색 근거 목록" exportRows={exportRows} exportFileName="admin-rag-evidence.csv">
      <Panel title="검색 근거 목록" subtitle="검색과 추천에 사용된 근거">
        {isLoading ? (
          <StateMessage type="info" title="검색 근거 로딩 중" body="관리자 검색 근거 목록을 불러오고 있습니다." />
        ) : isError ? (
          <StateMessage type="warn" title="검색 근거 조회 실패" body="관리자 검색 근거 목록 응답을 불러오지 못했습니다." />
        ) : rows.length ? (
          <DataTable columns={['식별자', '세션', '출처 식별자', '요약', '점수']} rows={rows} />
        ) : (
          <StateMessage type="info" title="검색 근거 없음" body="표시할 검색 근거가 없습니다." />
        )}
      </Panel>
    </AdminShell>
  );
}

function formatScore(value?: string | number | null) {
  return value == null ? '-' : String(value);
}
