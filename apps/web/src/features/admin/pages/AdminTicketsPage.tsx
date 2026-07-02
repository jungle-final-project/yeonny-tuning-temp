import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { getAdminTickets } from '../adminApi';
import type { AdminAsTicket } from '../adminApi';

export function AdminTicketsPage() {
  const { data, isError, isLoading } = useQuery({
    queryKey: ['admin-as-tickets'],
    queryFn: getAdminTickets
  });

  const tickets = data?.items ?? [];
  const ticketRows = tickets.map((ticket) => ({
    '티켓': <Link className="font-bold text-brand-blue" to={`/admin/as-tickets/${ticket.id}`}>{shortId(ticket.id)}</Link>,
    '상태': <StatusBadge status={ticket.status} />,
    '검수': ticket.reviewStatus ?? '-',
    '판정': ticket.supportDecision ?? '-',
    '증상': <Link className="font-bold text-slate-800 hover:text-brand-blue" to={`/admin/as-tickets/${ticket.id}`}>{ticket.title ?? firstLine(ticket.symptom)}</Link>,
    '사용자': userLabel(ticket),
    '접수 시간': formatDateTime(ticket.createdAt),
    '담당자': ticket.assignedAdminId ? shortId(ticket.assignedAdminId) : '미배정'
  }));

  const selected = tickets[0];

  return (
    <AdminShell title="AS 티켓 관리">
      <div className="grid grid-cols-[minmax(0,1fr)_420px] gap-5">
        <Panel title="처리할 AS 티켓" subtitle="사용자 증상과 PC Agent 로그가 접수된 티켓을 확인합니다.">
          {isLoading ? <StateMessage type="info" title="AS 티켓 로딩 중" body="관리자 AS 티켓 목록을 불러오고 있습니다." /> : null}
          {isError ? <StateMessage type="warn" title="AS 티켓 조회 실패" body="AS 티켓 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요." /> : null}
          {!isLoading && !isError && ticketRows.length === 0 ? (
            <StateMessage type="info" title="AS 티켓 없음" body="표시할 관리자 AS 티켓이 없습니다." />
          ) : null}
          {!isLoading && !isError && ticketRows.length > 0 ? (
            <DataTable columns={['티켓', '상태', '검수', '판정', '증상', '사용자', '접수 시간', '담당자']} rows={ticketRows} />
          ) : null}
        </Panel>

        <Panel title="최근 티켓 요약">
          {selected ? (
            <>
              <DataTable columns={['필드', '값']} rows={[
                { 필드: 'ticketId', 값: selected.id },
                { 필드: '상태', 값: <StatusBadge status={selected.status} /> },
                { 필드: '분석 상태', 값: selected.analysisStatus ?? '-' },
                { 필드: '검수 상태', 값: selected.reviewStatus ?? '-' },
                { 필드: '지원 판정', 값: selected.supportDecision ?? '-' },
                { 필드: '로그 업로드', 값: selected.logUploadId ?? '-' },
                { 필드: '원인 후보', 값: `${selected.causeCandidates.length}건` },
                { 필드: '추천 조치', 값: `${selected.upgradeCandidates.length}건` }
              ]} />
              <Link to={`/admin/as-tickets/${selected.id}`} className="mt-5 block rounded bg-brand-blue px-4 py-3 text-center text-sm font-bold text-white">상세 열기</Link>
            </>
          ) : (
            <StateMessage type="info" title="선택 티켓 없음" body="티켓이 생성되면 최근 항목 요약이 표시됩니다." />
          )}
        </Panel>
      </div>
    </AdminShell>
  );
}

function userLabel(ticket: AdminAsTicket) {
  return ticket.userEmail ?? ticket.userName ?? ticket.userId ?? '-';
}

function firstLine(value: string) {
  return value.split('\n').find((line) => line.trim())?.trim() ?? value;
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function formatDateTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}
