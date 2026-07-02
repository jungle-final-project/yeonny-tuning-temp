import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';

const smokeRows = [
  { target: '/api/health', purpose: 'API와 DB 연결 smoke', status: <StatusBadge status="ACTIVE" /> },
  { target: '/api/parts', purpose: '부품 조회 smoke', status: <StatusBadge status="ACTIVE" /> },
  { target: '/api/builds/recommend', purpose: '추천 흐름 mock smoke', status: <StatusBadge status="WARN" /> }
];

const loadPlanRows = [
  { phase: '2주차', target: '동시 300명', metric: '비LLM API p95 500ms 이하' },
  { phase: '4주차', target: '동시 1,000명', metric: '에러율 1% 이하' },
  { phase: 'LLM 제한 검증', target: '100~300건', metric: '비용과 대기시간 측정' }
];

const commandRows = [
  { command: 'cd apps/web && npm run test', purpose: '프론트 route/guard smoke' },
  { command: 'cd apps/api && ./gradlew test --no-daemon', purpose: '백엔드 controller/service 검증' },
  { command: 'k6 run infra/k6/smoke.js', purpose: '로컬 smoke 부하 테스트' }
];

const reportRows = [
  { artifact: 'infra/k6/reports/smoke-summary.json', owner: '5번', status: 'CLI 생성 후 보관' },
  { artifact: 'GitHub Actions summary', owner: '5번', status: 'CI 확장 시 연결' }
];

export function AdminLoadTestsPage() {
  const exportRows = [
    ...smokeRows.map((row) => ({ type: 'smoke-target', target: row.target, purpose: row.purpose, status: 'ACTIVE' })),
    ...loadPlanRows.map((row) => ({ type: 'load-plan', target: row.target, purpose: row.metric, status: row.phase })),
    ...commandRows.map((row) => ({ type: 'command', target: row.command, purpose: row.purpose, status: 'READY' }))
  ];

  return (
    <AdminShell title="부하 테스트" exportRows={exportRows} exportFileName="admin-load-tests.csv">
      <div className="grid grid-cols-[1fr_360px] gap-5">
        <Panel title="k6 Smoke 대상" subtitle="PR 전 빠르게 확인할 최소 endpoint">
          <DataTable columns={['target', 'purpose', 'status']} rows={smokeRows} />
        </Panel>
        <Panel title="리포트 상태">
          <StateMessage type="info" title="CLI/CI 실행 기준" body="관리자 화면은 k6를 직접 실행하지 않고, smoke 대상과 리포트 위치를 운영자가 확인하는 화면으로 둡니다." />
          <div className="mt-4">
            <DataTable columns={['artifact', 'owner', 'status']} rows={reportRows} />
          </div>
        </Panel>
        <Panel title="검증 명령" className="col-span-2">
          <DataTable columns={['command', 'purpose']} rows={commandRows} />
        </Panel>
        <Panel title="부하 검증 계획" className="col-span-2">
          <DataTable columns={['phase', 'target', 'metric']} rows={loadPlanRows} />
        </Panel>
      </div>
    </AdminShell>
  );
}
