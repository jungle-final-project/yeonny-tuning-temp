import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminShell, DataTable, Panel, StateMessage } from '../../../components/ui';
import { listPriceJobs, runPriceJob } from '../adminApi';
import { KoreanStatusBadge } from '../adminDisplay';

export function AdminPriceJobsPage() {
  const queryClient = useQueryClient();
  const jobsQuery = useQuery({
    queryKey: ['admin-price-jobs'],
    queryFn: listPriceJobs,
    refetchInterval: (query) => hasActiveJob(query.state.data?.items ?? []) ? 2000 : false
  });
  const runMutation = useMutation({
    mutationFn: runPriceJob,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin-price-jobs'] })
  });

  const jobs = jobsQuery.data?.items ?? [];
  const activeJob = hasActiveJob(jobs);
  const rows = jobs.map((job) => ({
    식별자: shortId(job.id),
    상태: <KoreanStatusBadge status={job.status} />,
    요청자: shortId(job.requestedBy ?? '-'),
    '시작 시간': formatDateTime(job.startedAt),
    '완료 시간': formatDateTime(job.finishedAt),
    '오류 요약': job.errorSummary ?? '-',
    '생성 시간': formatDateTime(job.createdAt)
  }));
  const exportRows = jobs.map((job) => ({
    id: job.id,
    status: job.status,
    requestedBy: job.requestedBy ?? '',
    startedAt: formatDateTime(job.startedAt),
    finishedAt: formatDateTime(job.finishedAt),
    errorSummary: job.errorSummary ?? '',
    createdAt: formatDateTime(job.createdAt)
  }));

  return (
    <AdminShell
      title="가격 작업 관리자"
      exportRows={exportRows}
      exportFileName="admin-price-jobs.csv"
      action={{
        label: activeJob ? '실행 중인 작업 있음' : runMutation.isPending ? '실행 요청 중' : '가격 작업 실행',
        onClick: () => runMutation.mutate(),
        disabled: runMutation.isPending || activeJob,
        title: '네이버 현재가 후보와 다나와 현재가 스냅샷 갱신 작업을 실행합니다.'
      }}
    >
      <div className="grid grid-cols-[minmax(0,1fr)_360px] gap-5">
        <Panel title="가격 수집 작업" subtitle="가격 작업과 작업 처리기 기준 상태">
          {jobsQuery.isLoading ? (
            <StateMessage type="info" title="가격 작업 로딩 중" body="가격 작업 목록을 조회하고 있습니다." />
          ) : jobsQuery.isError ? (
            <StateMessage type="warn" title="가격 작업 조회 실패" body="가격 작업 목록 응답을 불러오지 못했습니다." />
          ) : rows.length ? (
            <DataTable columns={['식별자', '상태', '요청자', '시작 시간', '완료 시간', '오류 요약', '생성 시간']} rows={rows} />
          ) : (
            <StateMessage type="info" title="가격 작업 없음" body="수동 실행 버튼으로 첫 가격 작업을 생성할 수 있습니다." />
          )}
        </Panel>
        <Panel title="실행 정책">
          <StateMessage type="info" title="작업 처리기 실행" body="실행 요청은 대기 작업을 만들고 작업 처리기가 실행 중, 성공 또는 실패 상태로 전이합니다. 네이버 쇼핑 연동과 다나와 가격 수집 키가 없어도 샘플/현재가 기준 상태 전이는 확인할 수 있습니다." />
          <button disabled={runMutation.isPending || activeJob} onClick={() => runMutation.mutate()} className="mt-5 w-full rounded bg-brand-blue px-4 py-3 text-sm font-bold text-white disabled:bg-slate-400">
            {activeJob ? '실행 중인 작업 있음' : runMutation.isPending ? '실행 요청 중' : '가격 작업 실행'}
          </button>
          {runMutation.isSuccess ? <StateMessage type="success" title="실행 요청 완료" body="가격 작업을 큐에 등록했습니다." /> : null}
          {runMutation.isError ? <StateMessage type="warn" title="실행 요청 실패" body="이미 실행 중인 작업이 있거나 관리자 권한을 확인해야 합니다." /> : null}
        </Panel>
      </div>
    </AdminShell>
  );
}

function hasActiveJob(jobs: Array<{ status: string }>) {
  return jobs.some((job) => job.status === 'QUEUED' || job.status === 'RUNNING');
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}
