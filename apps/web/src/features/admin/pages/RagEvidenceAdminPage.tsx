import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage } from '../../../components/ui';
import { getRagEvidence } from '../adminApi';
import type { RagEvidenceDetail } from '../adminApi';

export function RagEvidenceAdminPage() {
  const { id } = useParams();
  const {
    data: evidence,
    isError,
    isLoading
  } = useQuery({
    queryKey: ['admin-rag-evidence', id],
    queryFn: () => getRagEvidence(id ?? ''),
    enabled: Boolean(id)
  });

  if (!id) {
    return (
      <AdminShell title="검색 근거 상세">
        <StateMessage type="info" title="검색 근거를 선택하세요" body="에이전트 세션 상세에서 검색 근거 항목을 선택해야 합니다." />
      </AdminShell>
    );
  }

  if (isLoading) {
    return (
      <AdminShell title="검색 근거 상세">
        <StateMessage type="info" title="검색 근거 로딩 중" body="검색 근거 본문과 메타데이터를 불러오고 있습니다." />
      </AdminShell>
    );
  }

  if (isError || !evidence) {
    return (
      <AdminShell title="검색 근거 상세">
        <StateMessage type="warn" title="검색 근거 조회 실패" body="관리자 검색 근거 상세 응답을 불러오지 못했습니다." />
      </AdminShell>
    );
  }

  return (
    <AdminShell title="검색 근거 상세">
      <div className="grid grid-cols-[1fr_420px] gap-5">
        <Panel title="근거 문서" subtitle={`${evidence.sourceId} / ${evidence.id}`}>
          <DataTable columns={['필드', '값']} rows={detailRows(evidence)} />
        </Panel>
        <Panel title="요약">
          <StateMessage type="info" title={formatScore(evidence.score)} body={evidence.summary} />
        </Panel>
        <Panel title="근거 본문">
          <div className="min-h-[220px] rounded border border-slate-200 bg-white p-4 text-sm leading-7 text-slate-700">
            {evidence.chunkText ?? '관리자 응답에 근거 본문이 없습니다.'}
          </div>
        </Panel>
        <Panel title="메타데이터 JSON">
          <JsonBlock value={evidence.metadata ?? {}} />
        </Panel>
      </div>
    </AdminShell>
  );
}

function detailRows(evidence: RagEvidenceDetail) {
  return [
    { 필드: '근거 식별자', 값: evidence.id },
    { 필드: '출처 식별자', 값: evidence.sourceId },
    { 필드: '점수', 값: formatScore(evidence.score) },
    {
      필드: '에이전트 세션 식별자',
      값: evidence.agentSessionId
        ? <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${evidence.agentSessionId}`}>{evidence.agentSessionId}</Link>
        : '-'
    },
    { 필드: '요약', 값: evidence.summary }
  ];
}

function JsonBlock({ value }: { value: unknown }) {
  return (
    <pre className="max-h-[420px] overflow-auto rounded bg-slate-950 p-5 font-mono text-xs leading-6 text-slate-200">
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}

function formatScore(value?: string | number | null) {
  return value == null ? '점수 없음' : `점수 ${value}`;
}
