import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AdminShell, DataTable, MetricCard, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import {
  activateRecommendationModel,
  archiveRecommendationTrainingDataset,
  bulkExcludeRecommendationTrainingDatasetItems,
  bulkIncludeRecommendationTrainingDatasetItems,
  createHomePartRecommendationFeedback,
  createRecommendationTrainingDataset,
  createRecommendationTrainingJob,
  getAdminAgentSessions,
  getAdminDashboard,
  getRecentAdminAuditLogs,
  getRecommendationModels,
  getRecommendationModelSummary,
  getRecommendationTrainingOverview,
  listRecommendationTrainingDatasets,
  listRecommendationTrainingJobs,
  lockRecommendationTrainingDataset,
  retireRecommendationModel
} from '../adminApi';

function countLabel(value: number | null | undefined) {
  return `${value ?? 0}건`;
}

function percentLabel(value: number | null | undefined) {
  return `${Math.round((value ?? 0) * 1000) / 10}%`;
}

export function AdminDashboardPage() {
  const queryClient = useQueryClient();
  const [trainingTab, setTrainingTab] = useState<'overview' | 'datasets' | 'jobs' | 'models'>('overview');
  const { data: dashboard, isError, isLoading } = useQuery({
    queryKey: ['admin-dashboard'],
    queryFn: getAdminDashboard
  });
  const agentSessionsQuery = useQuery({
    queryKey: ['admin-agent-sessions'],
    queryFn: getAdminAgentSessions,
    enabled: Boolean(dashboard),
    retry: false
  });
  const auditLogsQuery = useQuery({
    queryKey: ['admin-audit-logs-recent'],
    queryFn: getRecentAdminAuditLogs,
    enabled: Boolean(dashboard),
    retry: false
  });
  const recommendationModelQuery = useQuery({
    queryKey: ['admin-recommendation-model-summary'],
    queryFn: getRecommendationModelSummary,
    enabled: Boolean(dashboard),
    retry: false
  });
  const trainingOverviewQuery = useQuery({
    queryKey: ['admin-recommendation-training-overview'],
    queryFn: getRecommendationTrainingOverview,
    enabled: Boolean(dashboard),
    retry: false
  });
  const trainingDatasetsQuery = useQuery({
    queryKey: ['admin-recommendation-training-datasets'],
    queryFn: listRecommendationTrainingDatasets,
    enabled: Boolean(dashboard),
    retry: false
  });
  const trainingJobsQuery = useQuery({
    queryKey: ['admin-recommendation-training-jobs'],
    queryFn: listRecommendationTrainingJobs,
    enabled: Boolean(dashboard),
    retry: false
  });
  const recommendationModelsQuery = useQuery({
    queryKey: ['admin-recommendation-models'],
    queryFn: getRecommendationModels,
    enabled: Boolean(dashboard),
    retry: false
  });
  const invalidateTraining = () => {
    queryClient.invalidateQueries({ queryKey: ['admin-recommendation-training-overview'] });
    queryClient.invalidateQueries({ queryKey: ['admin-recommendation-training-datasets'] });
    queryClient.invalidateQueries({ queryKey: ['admin-recommendation-training-jobs'] });
    queryClient.invalidateQueries({ queryKey: ['admin-recommendation-models'] });
    queryClient.invalidateQueries({ queryKey: ['admin-recommendation-model-summary'] });
  };
  const homePartFeedbackMutation = useMutation({
    mutationFn: createHomePartRecommendationFeedback,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin-recommendation-model-summary'] });
      queryClient.invalidateQueries({ queryKey: ['admin-recommendation-training-overview'] });
    }
  });
  const createDatasetMutation = useMutation({
    mutationFn: createRecommendationTrainingDataset,
    onSuccess: invalidateTraining
  });
  const lockDatasetMutation = useMutation({
    mutationFn: lockRecommendationTrainingDataset,
    onSuccess: invalidateTraining
  });
  const archiveDatasetMutation = useMutation({
    mutationFn: archiveRecommendationTrainingDataset,
    onSuccess: invalidateTraining
  });
  const createJobMutation = useMutation({
    mutationFn: createRecommendationTrainingJob,
    onSuccess: invalidateTraining
  });
  const bulkExcludeMutation = useMutation({
    mutationFn: ({ datasetId, eventType }: { datasetId: string; eventType: string }) => bulkExcludeRecommendationTrainingDatasetItems(datasetId, {
      eventType,
      reason: `${eventType} 이벤트를 학습에서 제외`
    }),
    onSuccess: invalidateTraining
  });
  const bulkIncludeMutation = useMutation({
    mutationFn: (datasetId: string) => bulkIncludeRecommendationTrainingDatasetItems(datasetId, {}),
    onSuccess: invalidateTraining
  });
  const activateModelMutation = useMutation({
    mutationFn: activateRecommendationModel,
    onSuccess: invalidateTraining
  });
  const retireModelMutation = useMutation({
    mutationFn: retireRecommendationModel,
    onSuccess: invalidateTraining
  });

  if (isLoading) {
    return (
      <AdminShell title="운영 대시보드">
        <StateMessage type="info" title="대시보드 로딩 중" body="운영 지표를 불러오고 있습니다." />
      </AdminShell>
    );
  }

  if (isError || !dashboard) {
    return (
      <AdminShell title="운영 대시보드">
        <StateMessage type="warn" title="대시보드 조회 실패" body="관리자 대시보드 API 응답을 불러오지 못했습니다." />
      </AdminShell>
    );
  }

  const statusLabel = dashboard.degraded ? '주의' : '정상';
  const generatedAt = dashboard.generatedAt ?? '갱신 시간 없음';
  const dashboardExportRows = [
    { metric: 'agentRunning', value: dashboard.agentRunning, generatedAt },
    { metric: 'openTickets', value: dashboard.openTickets, generatedAt },
    { metric: 'priceJobsRunning', value: dashboard.priceJobsRunning, generatedAt },
    { metric: 'degraded', value: dashboard.degraded, generatedAt },
    {
      metric: 'homeRecommendationCtr',
      value: recommendationModelQuery.data?.homeParts?.ctr ?? 0,
      generatedAt: recommendationModelQuery.data?.generatedAt ?? generatedAt
    }
  ];
  const agentSessionRows = (agentSessionsQuery.data?.items ?? []).slice(0, 5).map((session) => ({
    세션: session.id,
    상태: <StatusBadge status={session.status} />,
    사용자: session.userId ?? '-',
    생성일: session.createdAt ?? '-',
    이동: <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${session.id}`}>상세</Link>
  }));
  const firstAgentSessionId = agentSessionsQuery.data?.items?.[0]?.id;
  const auditLogRows = (auditLogsQuery.data?.items ?? []).map((item) => ({
    action: item.action ?? 'UNKNOWN',
    targetType: item.targetType ?? 'unknown',
    targetId: item.targetId ?? '-',
    createdAt: item.createdAt ?? '시간 없음'
  }));
  const recommendationSummary = recommendationModelQuery.data;
  const scoreSourceRows = (recommendationSummary?.homeParts.scoreSources ?? []).map((item) => ({
    scoreSource: item.scoreSource,
    count: countLabel(item.count),
    share: percentLabel(item.share)
  }));
  const recentHomePartRows = (recommendationSummary?.homeParts.recentCandidates ?? []).map((item) => ({
    category: item.category,
    part: (
      <div>
        <div className="font-bold text-commerce-ink">{item.name}</div>
        <div className="text-xs text-slate-500">{item.manufacturer ?? '-'} · {item.price ? `${item.price.toLocaleString()}원` : '가격 없음'}</div>
      </div>
    ),
    score: item.score ?? '-',
    action: (
      <div className="flex gap-2">
        <button
          type="button"
          className="rounded border border-emerald-200 px-2 py-1 text-xs font-black text-emerald-700 hover:bg-emerald-50 disabled:opacity-50"
          disabled={homePartFeedbackMutation.isPending}
          onClick={() => homePartFeedbackMutation.mutate({
            partId: item.partId,
            label: 'PROMOTE',
            reason: '관리자 홈 추천 강화',
            category: item.category,
            rankPosition: item.rankPosition
          })}
        >
          추천 강화
        </button>
        <button
          type="button"
          className="rounded border border-rose-200 px-2 py-1 text-xs font-black text-rose-700 hover:bg-rose-50 disabled:opacity-50"
          disabled={homePartFeedbackMutation.isPending}
          onClick={() => homePartFeedbackMutation.mutate({
            partId: item.partId,
            label: 'DEMOTE',
            reason: '관리자 홈 추천 낮춤',
            category: item.category,
            rankPosition: item.rankPosition
          })}
        >
          추천 낮춤
        </button>
      </div>
    )
  }));
  const trainingOverview = trainingOverviewQuery.data;
  const trainingDatasetRows = (trainingDatasetsQuery.data?.items ?? []).map((item) => ({
    name: (
      <div>
        <div className="font-bold text-commerce-ink">{item.name}</div>
        <div className="text-xs text-slate-500">{item.id}</div>
      </div>
    ),
    status: <StatusBadge status={item.status} />,
    counts: `${item.includedCount}/${item.eligibleCount} 포함 · 제외 ${item.excludedCount}`,
    createdAt: item.createdAt ?? '-',
    action: (
      <div className="flex flex-wrap gap-2">
        {item.status === 'DRAFT' ? (
          <>
            <button
              type="button"
              className="rounded border border-slate-200 px-2 py-1 text-xs font-black text-commerce-ink hover:bg-slate-50 disabled:opacity-50"
              disabled={bulkExcludeMutation.isPending}
              onClick={() => bulkExcludeMutation.mutate({ datasetId: item.id, eventType: 'IMPRESSION' })}
            >
              노출 제외
            </button>
            <button
              type="button"
              className="rounded border border-slate-200 px-2 py-1 text-xs font-black text-commerce-ink hover:bg-slate-50 disabled:opacity-50"
              disabled={bulkIncludeMutation.isPending}
              onClick={() => bulkIncludeMutation.mutate(item.id)}
            >
              전체 포함
            </button>
            <button
              type="button"
              className="rounded bg-commerce-ink px-2 py-1 text-xs font-black text-white disabled:opacity-50"
              disabled={lockDatasetMutation.isPending}
              onClick={() => lockDatasetMutation.mutate(item.id)}
            >
              잠금
            </button>
          </>
        ) : null}
        {item.status === 'LOCKED' ? (
          <button
            type="button"
            className="rounded bg-brand-blue px-2 py-1 text-xs font-black text-white disabled:opacity-50"
            disabled={createJobMutation.isPending}
            onClick={() => createJobMutation.mutate({ datasetId: item.id })}
          >
            학습 Job 생성
          </button>
        ) : null}
        {item.status !== 'ARCHIVED' ? (
          <button
            type="button"
            className="rounded border border-rose-200 px-2 py-1 text-xs font-black text-rose-700 hover:bg-rose-50 disabled:opacity-50"
            disabled={archiveDatasetMutation.isPending}
            onClick={() => archiveDatasetMutation.mutate(item.id)}
          >
            보관
          </button>
        ) : null}
      </div>
    )
  }));
  const trainingJobRows = (trainingJobsQuery.data?.items ?? []).map((item) => ({
    dataset: item.datasetName ?? item.datasetId,
    status: <StatusBadge status={item.status} />,
    modelVersion: item.modelVersion ?? '-',
    rows: typeof item.metrics?.rowCount === 'number' ? `${item.metrics.rowCount}건` : '-',
    log: item.logSummary ?? '-',
    createdAt: item.createdAt ?? '-'
  }));
  const modelVersionRows = (recommendationModelsQuery.data?.items ?? []).map((item) => ({
    modelVersion: (
      <div>
        <div className="font-bold text-commerce-ink">{item.modelVersion}</div>
        <div className="text-xs text-slate-500">{item.artifactPath ?? 'artifact 없음'}</div>
      </div>
    ),
    status: <StatusBadge status={item.status} />,
    metric: item.metrics?.mae !== undefined ? `MAE ${Number(item.metrics.mae).toFixed(3)}` : '-',
    createdAt: item.createdAt ?? '-',
    action: (
      <div className="flex flex-wrap gap-2">
        {item.status === 'SHADOW' ? (
          <button
            type="button"
            className="rounded bg-emerald-600 px-2 py-1 text-xs font-black text-white disabled:opacity-50"
            disabled={activateModelMutation.isPending}
            onClick={() => {
              if (window.confirm(`${item.modelVersion} 모델을 홈 추천부품 ACTIVE로 전환할까요?`)) {
                activateModelMutation.mutate(item.id);
              }
            }}
          >
            활성화
          </button>
        ) : null}
        {item.status !== 'RETIRED' ? (
          <button
            type="button"
            className="rounded border border-rose-200 px-2 py-1 text-xs font-black text-rose-700 hover:bg-rose-50 disabled:opacity-50"
            disabled={retireModelMutation.isPending}
            onClick={() => {
              if (window.confirm(`${item.modelVersion} 모델을 은퇴 처리할까요?`)) {
                retireModelMutation.mutate(item.id);
              }
            }}
          >
            은퇴
          </button>
        ) : null}
      </div>
    )
  }));
  const trainingMutationError =
    createDatasetMutation.isError ||
    lockDatasetMutation.isError ||
    archiveDatasetMutation.isError ||
    createJobMutation.isError ||
    bulkExcludeMutation.isError ||
    bulkIncludeMutation.isError ||
    activateModelMutation.isError ||
    retireModelMutation.isError;
  const operatingTasks = [
    {
      작업: '가격 Job',
      상태: <StatusBadge status={dashboard.priceJobsRunning > 0 ? 'RUNNING' : 'READY'} />,
      owner: '2번',
      이동: <Link className="font-bold text-brand-blue" to="/admin/price-jobs">Job 확인</Link>
    },
    {
      작업: 'Mailpit',
      상태: <StatusBadge status="READY" />,
      owner: '5번',
      이동: 'Infra'
    },
    {
      작업: 'Mock Worker',
      상태: <StatusBadge status="READY" />,
      owner: '3번/5번',
      이동: firstAgentSessionId
        ? <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${firstAgentSessionId}`}>Agent/RAG</Link>
        : '세션 없음'
    },
    {
      작업: 'k6 Smoke',
      상태: <StatusBadge status="TODO" />,
      owner: '5번',
      이동: <Link className="font-bold text-brand-blue" to="/admin/load-tests">리포트</Link>
    }
  ];
  const adminTodos = [
    {
      영역: '부품/가격',
      owner: '2번',
      처리: <Link className="font-bold text-brand-blue" to="/admin/parts">요약 확인</Link>
    },
    {
      영역: 'Agent/RAG',
      owner: '3번',
      처리: firstAgentSessionId
        ? <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${firstAgentSessionId}`}>세션 확인</Link>
        : '세션 없음'
    },
    {
      영역: 'AS 티켓',
      owner: '4번',
      처리: <Link className="font-bold text-brand-blue" to="/admin/as-tickets">티켓 확인</Link>
    },
    {
      영역: '공통 권한',
      owner: '5번',
      처리: 'guard/API 계약 검토'
    }
  ];

  return (
    <AdminShell title="운영 대시보드" exportRows={dashboardExportRows} exportFileName="admin-dashboard.csv">
      {dashboard.degraded ? (
        <div className="mb-4">
          <StateMessage
            type="warn"
            title="운영 상태 주의"
            body={`일부 운영 지표가 주의 상태입니다. 마지막 갱신: ${generatedAt}`}
          />
        </div>
      ) : null}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard label="진행 중 Agent" value={countLabel(dashboard.agentRunning)} tone="orange" />
        <MetricCard label="미해결 AS" value={countLabel(dashboard.openTickets)} tone="orange" />
        <MetricCard label="실행 중 Price Job" value={countLabel(dashboard.priceJobsRunning)} tone="blue" />
        <MetricCard label="운영 상태" value={statusLabel} tone={dashboard.degraded ? 'orange' : 'green'} />
      </div>
      <div className="mt-5 grid grid-cols-1 gap-5 xl:grid-cols-[1fr_420px]">
        <Panel title="최근 Agent 세션 요약">
          {agentSessionsQuery.isLoading ? (
            <StateMessage type="info" title="Agent 세션 로딩 중" body="최근 Agent 세션을 불러오고 있습니다." />
          ) : agentSessionsQuery.isError ? (
            <StateMessage type="warn" title="Agent 세션 조회 실패" body="관리자 Agent 세션 목록 API 응답을 불러오지 못했습니다." />
          ) : agentSessionRows.length > 0 ? (
            <DataTable columns={['세션', '상태', '사용자', '생성일', '이동']} rows={agentSessionRows} />
          ) : (
            <StateMessage type="info" title="Agent 세션 없음" body="표시할 Agent 세션이 없습니다." />
          )}
        </Panel>
        <Panel title="운영 상태">
          <StateMessage
            type={dashboard.degraded ? 'warn' : 'success'}
            title={dashboard.degraded ? '운영 지표 확인 필요' : '운영 상태 정상'}
            body={`마지막 갱신: ${generatedAt}`}
          />
        </Panel>
      </div>
      <div className="mt-5">
        <Panel title="AI 추천 모델 상태" subtitle="홈 하단 추천부품 XGBoost scorer와 HOME 추천 이벤트 기준">
          {recommendationModelQuery.isLoading ? (
            <StateMessage type="info" title="추천 모델 상태 로딩 중" body="최근 모델 버전과 홈 추천부품 반응 지표를 불러오고 있습니다." />
          ) : recommendationModelQuery.isError || !recommendationSummary ? (
            <StateMessage type="warn" title="추천 모델 상태 조회 실패" body="추천 모델 요약 API 응답을 불러오지 못했습니다." />
          ) : (
            <div className="grid gap-4 xl:grid-cols-[1fr_1fr]">
              <DataTable
                columns={['metric', 'value']}
                rows={[
                  { metric: 'latestModel', value: recommendationSummary.latestModel?.modelVersion ?? '모델 없음' },
                  { metric: 'status', value: <StatusBadge status={recommendationSummary.latestModel?.status ?? 'FALLBACK'} /> },
                  { metric: 'HOME impressions', value: countLabel(recommendationSummary.homeParts.impressions) },
                  { metric: 'HOME clicks', value: countLabel(recommendationSummary.homeParts.clicks) },
                  { metric: 'HOME CTR', value: percentLabel(recommendationSummary.homeParts.ctr) },
                  { metric: 'shadow scores', value: countLabel(recommendationSummary.homeParts.recentShadowScores) }
                ]}
              />
              {scoreSourceRows.length > 0 ? (
                <DataTable columns={['scoreSource', 'count', 'share']} rows={scoreSourceRows} />
              ) : (
                <StateMessage type="info" title="HOME 추천 이벤트 없음" body="홈 추천부품 노출/클릭 이벤트가 쌓이면 score source 비율이 표시됩니다." />
              )}
              <div className="xl:col-span-2">
                {recentHomePartRows.length > 0 ? (
                  <DataTable columns={['category', 'part', 'score', 'action']} rows={recentHomePartRows} />
                ) : (
                  <StateMessage type="info" title="최근 추천 후보 없음" body="홈 추천부품이 노출되면 관리자 라벨링 후보가 표시됩니다." />
                )}
                {homePartFeedbackMutation.isError ? (
                  <p className="mt-3 text-xs font-bold text-rose-600">추천 라벨 저장에 실패했습니다.</p>
                ) : null}
              </div>
              <div className="xl:col-span-2 border-t border-slate-200 pt-4">
                <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <h3 className="text-sm font-black text-commerce-ink">XGBoost 학습 운영</h3>
                    <p className="text-xs text-slate-500">데이터셋 생성, 학습 Job, Shadow 모델 활성화를 관리합니다.</p>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {(['overview', 'datasets', 'jobs', 'models'] as const).map((tab) => (
                      <button
                        key={tab}
                        type="button"
                        className={`rounded border px-3 py-2 text-xs font-black ${trainingTab === tab ? 'border-commerce-ink bg-commerce-ink text-white' : 'border-slate-200 text-slate-600 hover:bg-slate-50'}`}
                        onClick={() => setTrainingTab(tab)}
                      >
                        {tab === 'overview' ? '요약' : tab === 'datasets' ? '데이터셋' : tab === 'jobs' ? '학습 Job' : '모델 버전'}
                      </button>
                    ))}
                  </div>
                </div>
                {trainingOverviewQuery.isLoading ? (
                  <StateMessage type="info" title="학습 운영 지표 로딩 중" body="학습 이벤트와 모델 상태를 불러오고 있습니다." />
                ) : trainingOverviewQuery.isError || !trainingOverview ? (
                  <StateMessage type="warn" title="학습 운영 지표 조회 실패" body="추천 학습 운영 API 응답을 불러오지 못했습니다." />
                ) : trainingTab === 'overview' ? (
                  <DataTable
                    columns={['metric', 'value']}
                    rows={[
                      { metric: 'eligible events', value: countLabel(trainingOverview.eligibleEvents) },
                      { metric: 'trained distinct events', value: countLabel(trainingOverview.trainedDistinctEvents) },
                      { metric: 'untrained eligible events', value: countLabel(trainingOverview.untrainedEligibleEvents) },
                      { metric: 'excluded dataset items', value: countLabel(trainingOverview.excludedDatasetItems) },
                      { metric: 'recent 7d events', value: countLabel(trainingOverview.recentSevenDayEvents) },
                      { metric: 'AS confirmed feedback', value: countLabel(trainingOverview.asFeedbackEvents ?? 0) },
                      { metric: 'untrained AS feedback', value: countLabel(trainingOverview.untrainedAsFeedbackEvents ?? 0) },
                      { metric: 'active model', value: trainingOverview.activeModel?.modelVersion ?? '활성 모델 없음' },
                      { metric: 'latest job', value: trainingOverview.latestJob ? <StatusBadge status={trainingOverview.latestJob.status} /> : 'Job 없음' }
                    ]}
                  />
                ) : trainingTab === 'datasets' ? (
                  <div className="space-y-3">
                    <button
                      type="button"
                      className="rounded bg-brand-blue px-3 py-2 text-xs font-black text-white disabled:opacity-50"
                      disabled={createDatasetMutation.isPending}
                      onClick={() => createDatasetMutation.mutate({ name: `홈 추천부품+AS 피드백 학습 데이터셋 ${new Date().toISOString().slice(0, 10)}` })}
                    >
                      현재 HOME/AS 피드백으로 데이터셋 생성
                    </button>
                    {trainingDatasetRows.length > 0 ? (
                      <DataTable columns={['name', 'status', 'counts', 'createdAt', 'action']} rows={trainingDatasetRows} />
                    ) : (
                      <StateMessage type="info" title="학습 데이터셋 없음" body="HOME 추천 이벤트가 쌓이면 데이터셋을 생성할 수 있습니다." />
                    )}
                  </div>
                ) : trainingTab === 'jobs' ? (
                  trainingJobRows.length > 0 ? (
                    <DataTable columns={['dataset', 'status', 'modelVersion', 'rows', 'log', 'createdAt']} rows={trainingJobRows} />
                  ) : (
                    <StateMessage type="info" title="학습 Job 없음" body="LOCKED 데이터셋에서 학습 Job을 생성하면 여기에 표시됩니다." />
                  )
                ) : modelVersionRows.length > 0 ? (
                  <DataTable columns={['modelVersion', 'status', 'metric', 'createdAt', 'action']} rows={modelVersionRows} />
                ) : (
                  <StateMessage type="info" title="모델 버전 없음" body="학습 Job이 성공하면 SHADOW 모델 버전이 표시됩니다." />
                )}
                {trainingMutationError ? (
                  <p className="mt-3 text-xs font-bold text-rose-600">추천 학습 운영 작업에 실패했습니다. 상태와 권한, scorer 연결을 확인하십시오.</p>
                ) : null}
              </div>
            </div>
          )}
        </Panel>
      </div>
      <div className="mt-5 grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Panel title="운영 작업">
          <DataTable columns={['작업', '상태', 'owner', '이동']} rows={operatingTasks} />
        </Panel>
        <Panel title="관리자 할 일">
          <DataTable columns={['영역', 'owner', '처리']} rows={adminTodos} />
        </Panel>
      </div>
      <div className="mt-5">
        <Panel title="최근 관리자 작업" subtitle="admin_audit_logs 기준 최근 작업">
          {auditLogsQuery.isLoading ? (
            <StateMessage type="info" title="감사 로그 로딩 중" body="최근 관리자 작업을 불러오고 있습니다." />
          ) : auditLogsQuery.isError ? (
            <StateMessage type="warn" title="감사 로그 조회 실패" body="최근 관리자 작업을 불러오지 못했습니다." />
          ) : auditLogRows.length > 0 ? (
            <DataTable columns={['action', 'targetType', 'targetId', 'createdAt']} rows={auditLogRows} />
          ) : (
            <StateMessage type="info" title="감사 로그 없음" body="표시할 최근 관리자 작업이 없습니다." />
          )}
        </Panel>
      </div>
    </AdminShell>
  );
}
