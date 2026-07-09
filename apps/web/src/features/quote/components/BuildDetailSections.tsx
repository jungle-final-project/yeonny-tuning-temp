import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { DataTable, MetricCard, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { BENCHMARK_REFERENCE_NOTICE } from '../../../lib/disclaimers';
import type { AiAssistantSession, AiRecommendedBuild } from '../aiSelection';
import type { BuildSummary, ToolResult, WarningDto } from '../types';

export function BuildDetailSections({
  displayBuild,
  conditionBody,
  summaryNotice,
  summaryActions
}: {
  displayBuild: BuildSummary;
  conditionBody: string;
  summaryNotice?: ReactNode;
  summaryActions: ReactNode;
}) {
  const toolResults = displayBuild.toolResults ?? [];
  const passCount = toolResults.filter((row) => row.status === 'PASS').length;

  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
      <div className="min-w-0 space-y-5">
        <Panel title="구성 부품">
          <div className="space-y-2 md:hidden">
            {displayBuild.items.map((item) => (
              <div key={`${displayBuild.id}-${item.category}`} className="rounded-md border border-commerce-line bg-white p-3">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-xs font-black text-slate-500">{item.category}</span>
                  <span className="whitespace-nowrap text-sm font-black text-brand-blue">{item.price.toLocaleString()}원</span>
                </div>
                <div className="mt-2 text-sm font-black leading-5 text-commerce-ink">
                  {item.partId ? (
                    <Link to={`/parts/${item.partId}`} className="hover:text-brand-blue hover:underline">{item.name}</Link>
                  ) : (
                    item.name
                  )}
                </div>
                <div className="mt-1 text-xs font-semibold text-slate-500">{item.manufacturer ?? '-'}</div>
              </div>
            ))}
          </div>
          <div className="hidden md:block">
            <DataTable columns={['분류', '부품명', '제조사', '가격']} rows={displayBuild.items.map((item) => ({
              분류: item.category,
              부품명: item.partId ? (
                <Link to={`/parts/${item.partId}`} className="font-bold text-commerce-ink hover:text-brand-blue hover:underline">{item.name}</Link>
              ) : (
                item.name
              ),
              제조사: item.manufacturer ?? '-',
              가격: <span className="whitespace-nowrap font-black text-commerce-ink">{item.price.toLocaleString()}원</span>
            }))} />
          </div>
        </Panel>
        <Panel title="Tool 검증 결과">
          {toolResults.length > 0 ? (
            <div className={`mb-3 rounded-md border px-4 py-3 text-sm font-black ${passCount === toolResults.length ? 'border-emerald-100 bg-emerald-50 text-emerald-700' : 'border-amber-100 bg-amber-50 text-amber-700'}`}>
              {passCount === toolResults.length
                ? `${toolResults.length}개 검증 모두 통과`
                : `${toolResults.length}개 검증 중 ${passCount}개 통과 · 아래에서 경고 항목을 확인하세요`}
            </div>
          ) : null}
          <div className="space-y-2 md:hidden">
            {toolResults.map((row) => (
              <div key={`${row.tool}-${row.summary}`} className="rounded-md border border-commerce-line bg-white p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-xs font-black text-slate-500">{toolLabel(row.tool)}</span>
                  <span className="flex items-center gap-2">
                    <StatusBadge status={row.status} />
                    <StatusBadge status={row.confidence} />
                  </span>
                </div>
                <p className="mt-2 text-xs leading-5 text-slate-600">{row.summary}</p>
              </div>
            ))}
          </div>
          <div className="hidden md:block">
            <DataTable columns={['검증 항목', '상태', '신뢰도', '요약']} rows={toolRows(toolResults)} />
          </div>
          <p className="mt-3 break-keep text-[11px] font-bold leading-5 text-slate-500">{BENCHMARK_REFERENCE_NOTICE}</p>
        </Panel>
      </div>
      <Panel title="견적 요약 / 액션">
        <div className="space-y-4">
          <MetricCard label="총액" value={`${displayBuild.totalPrice.toLocaleString()}원`} />
          <div className="grid grid-cols-2 gap-3">
            <MetricCard label="부품 수" value={`${displayBuild.items.length}개`} />
            <MetricCard label="경고" value={displayBuild.warnings.length > 0 ? `${displayBuild.warnings.length}건` : '없음'} />
          </div>
          <StateMessage
            type={displayBuild.warnings.length > 0 ? 'warn' : 'success'}
            title={displayBuild.warnings.length > 0 ? '확인 필요' : '주요 조건 충족'}
            body={displayBuild.warnings[0]?.message ?? conditionBody}
          />
          {summaryNotice}
          {summaryActions}
        </div>
      </Panel>
    </div>
  );
}

export function temporaryBuildToBuildSummary(build: AiRecommendedBuild): BuildSummary {
  return {
    id: build.id,
    name: build.title,
    recommendedFor: build.tierLabel,
    summary: build.summary,
    totalPrice: build.totalPrice,
    confidence: build.confidence ?? 'MEDIUM',
    items: build.items.map((item) => ({
      id: item.partId,
      partId: item.partId,
      category: item.category,
      name: item.name,
      manufacturer: item.manufacturer,
      price: item.price * item.quantity,
      status: 'ACTIVE',
      attributes: { note: item.note, quantity: item.quantity }
    })),
    warnings: warningDtos(build.warnings ?? []),
    evidenceIds: [],
    agentSessionId: null,
    agentSummary: null,
    changeableCategories: ['CPU', 'GPU', 'RAM', 'STORAGE', 'PSU', 'CASE', 'COOLER'],
    toolResults: build.toolResults ?? []
  };
}

export function latestUserMessage(session: AiAssistantSession) {
  return [...session.messages].reverse().find((message) => message.role === 'user')?.text;
}

function warningDtos(warnings: string[]): WarningDto[] {
  return warnings.map((message) => ({ message, severity: 'WARN' }));
}

function toolRows(results: ToolResult[]) {
  return results.map((row) => ({
    '검증 항목': toolLabel(row.tool),
    상태: <StatusBadge status={row.status} />,
    신뢰도: <StatusBadge status={row.confidence} />,
    요약: row.summary
  }));
}

function toolLabel(tool: string) {
  switch (tool) {
    case 'compatibility':
      return '호환성 검증';
    case 'power':
      return '전력 검증';
    case 'size':
      return '규격 검증';
    case 'performance':
      return '성능 범위';
    case 'price':
      return '가격 확인';
    default:
      return tool;
  }
}
