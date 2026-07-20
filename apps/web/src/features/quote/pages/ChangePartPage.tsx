import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { DataTable, Panel, Screen, StateMessage, StatusBadge } from '../../../components/ui';
import { BENCHMARK_REFERENCE_NOTICE } from '../../../lib/disclaimers';
import { listParts } from '../../parts/partsApi';
import type { PartRow } from '../../parts/types';
import { PART_CATEGORY_LABELS } from '../aiSelection';
import { changePart, getBuild } from '../quoteApi';
import type { ToolResult } from '../types';

const changeCategories = [
  ['CPU', 'CPU'],
  ['GPU', 'GPU'],
  ['RAM', 'RAM'],
  ['STORAGE', 'SSD'],
  ['PSU', '파워'],
  ['CASE', '케이스'],
  ['COOLER', '쿨러']
] as const;

export function ChangePartPage() {
  const { buildId = '00000000-0000-4000-8000-000000002001' } = useParams();
  const [category, setCategory] = useState('GPU');
  const { data: build } = useQuery({
    queryKey: ['build', buildId],
    queryFn: () => getBuild(buildId)
  });
  const { data: partsPage, isLoading: partsLoading, isError: partsError } = useQuery({
    queryKey: ['parts', 'change-part', category],
    queryFn: () => listParts({ category, page: 0, size: 12, sort: 'price_asc' })
  });
  const compareMutation = useMutation({
    mutationFn: (part: PartRow) => changePart(buildId, category, part.id)
  });
  const comparison = compareMutation.data;

  return (
    <Screen>
      <div className="grid grid-cols-[330px_1fr_360px] gap-5">
        <Panel title="변경 후보 부품" subtitle="카테고리를 고르고 후보를 선택하면 비교가 실행됩니다.">
          <div className="mb-4 grid grid-cols-2 gap-2">
            {changeCategories.map(([value, label]) => (
              <button
                key={value}
                type="button"
                onClick={() => setCategory(value)}
                className={`rounded border px-3 py-2 text-sm font-bold ${category === value ? 'border-brand-blue bg-brand-pale text-brand-blue' : 'border-slate-200 bg-white text-slate-700'}`}
              >
                {label}
              </button>
            ))}
          </div>
          {partsLoading ? <StateMessage type="info" title="후보 로딩 중" body="내부 자산에서 변경 후보를 불러오고 있습니다." /> : null}
          {partsError ? <StateMessage type="warn" title="후보 조회 실패" body="변경 후보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요." /> : null}
          {!partsLoading && !partsError && (partsPage?.items ?? []).length === 0 ? <StateMessage type="info" title="후보 없음" body="표시할 변경 후보가 없습니다." /> : null}
          <div className="space-y-2">
            {(partsPage?.items ?? []).map((part) => (
              <button
                key={part.id}
                type="button"
                onClick={() => compareMutation.mutate(part)}
                className="w-full rounded border border-slate-200 bg-white p-3 text-left hover:border-brand-blue"
              >
                <div className="font-bold text-slate-900">{part.name}</div>
                <div className="mt-1 text-xs text-slate-500">{part.manufacturer ?? '-'} · {part.price.toLocaleString()}원</div>
              </button>
            ))}
          </div>
        </Panel>

        <Panel title="부품 변경 비교 / 검증" subtitle={`견적 ${build?.name ?? buildId}`}>
          {!comparison && !compareMutation.isPending ? <StateMessage type="info" title="비교 대기" body="왼쪽에서 변경할 부품을 선택하면 가격, 성능, 전력, 호환성 차이를 계산합니다." /> : null}
          {compareMutation.isPending ? <StateMessage type="info" title="비교 실행 중" body="선택한 부품으로 구성과 검증 결과를 다시 계산하고 있습니다." /> : null}
          {compareMutation.isError ? <StateMessage type="warn" title="비교 실패" body="선택한 부품의 변경 비교 API를 불러오지 못했습니다." /> : null}
          {comparison ? (
            <div className="space-y-5">
              <DataTable columns={['항목', '변경 전', '변경 후', '차이', '상태']} rows={comparison.diffRows.map((row) => ({
                항목: row.label,
                '변경 전': row.before,
                '변경 후': row.after,
                차이: row.diff,
                상태: <StatusBadge status={row.status} />
              }))} />
              <DataTable columns={['카테고리', '부품명', '가격']} rows={comparison.afterBuild.items.map((item) => ({
                카테고리: (PART_CATEGORY_LABELS as Record<string, string>)[item.category] ?? item.category,
                부품명: item.name,
                가격: `${item.price.toLocaleString()}원`
              }))} />
            </div>
          ) : null}
        </Panel>

        <Panel title="적용 결과">
          {comparison ? (
            <div className="space-y-4">
              <StateMessage
                type={comparison.warnings.length > 0 ? 'warn' : 'success'}
                title={comparison.warnings.length > 0 ? '조건부 적용 가능' : '적용 가능'}
                body={comparison.warnings[0]?.message ?? '변경 후 구성도 주요 자동 검증을 통과했습니다.'}
              />
              <DataTable columns={['검증 항목', '상태', '신뢰도', '요약']} rows={toolRows(comparison.toolResults)} />
              <p className="break-keep text-[11px] font-bold leading-5 text-slate-500">{BENCHMARK_REFERENCE_NOTICE}</p>
              <StateMessage type="info" title="AI 설명" body={comparison.agentSummary ?? 'AI 설명은 실행 환경에 따라 비어 있을 수 있습니다.'} />
              <div className="space-y-3">
                <Link to={`/builds/${buildId}`} className="block rounded bg-brand-blue px-4 py-3 text-center text-sm font-bold text-white">상세로 돌아가기</Link>
                <Link to="/self-quote" className="block rounded border border-slate-300 px-4 py-3 text-center text-sm font-bold">셀프 견적 보기</Link>
              </div>
            </div>
          ) : (
            <StateMessage type="info" title="후보 선택 필요" body="비교할 부품을 선택하면 적용 가능 여부와 검증 결과가 표시됩니다." />
          )}
        </Panel>
      </div>
    </Screen>
  );
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
