import { Link } from 'react-router-dom';
import { StatusBadge } from '../../../components/feedback/StatusBadge';
import type { BuildSummary } from '../types';

export function QuoteCard({ build, selected = false, showActions = true, onSelect }: { build: BuildSummary; selected?: boolean; showActions?: boolean; onSelect?: (build: BuildSummary) => void }) {
  const warnings = build.warnings ?? [];
  const primaryWarning = warnings[0]?.message;
  const mainItems = (build.items ?? []).slice(0, 5);
  return (
    <div className={`panel w-[300px] p-4 ${selected ? 'border-[#de6c2d] ring-2 ring-[#f4c8b2]' : ''}`}>
      <button type="button" onClick={() => onSelect?.(build)} className="block w-full text-left">
        <div className="mb-3 rounded border border-slate-200 bg-slate-50 p-3">
          <div className="text-xs font-bold text-[#de6c2d]">{build.recommendedFor ?? '맞춤 추천'}</div>
          <div className="mt-1 text-lg font-bold text-slate-950">{build.name}</div>
          <div className="mt-1 min-h-8 text-xs leading-4 text-slate-500">{build.summary ?? '내부 자산과 저장된 현재가 기준으로 구성했습니다.'}</div>
        </div>
        <div className="text-2xl font-bold text-[#de6c2d]">{build.totalPrice.toLocaleString()}원</div>
        <div className="mt-3 flex items-center gap-2">
          <StatusBadge status={build.confidence} />
          {primaryWarning ? <span className="truncate text-xs text-orange-600">{primaryWarning}</span> : <span className="text-xs text-emerald-700">주요 조건 충족</span>}
        </div>
        <div className="mt-4 space-y-2">
          {mainItems.map((item) => (
            <div key={`${build.id}-${item.category}`} className="flex items-center justify-between gap-2 text-xs">
              <span className="w-20 font-bold text-slate-500">{labelForCategory(item.category)}</span>
              <span className="flex-1 truncate text-slate-800">{item.name}</span>
            </div>
          ))}
        </div>
      </button>
      {showActions ? (
        <div className="mt-4 flex gap-2">
          <Link to={`/builds/${build.id}`} className="rounded bg-[#de6c2d] px-3 py-2 text-xs font-semibold text-white hover:bg-[#c45c22]">상세 보기</Link>
          <Link to={`/builds/${build.id}/change-part`} className="rounded border border-slate-300 px-3 py-2 text-xs font-semibold">부품 변경</Link>
        </div>
      ) : null}
    </div>
  );
}

function labelForCategory(category: string) {
  switch (category) {
    case 'MOTHERBOARD':
      return '메인보드';
    case 'STORAGE':
      return 'SSD';
    case 'PSU':
      return '파워';
    case 'CASE':
      return '케이스';
    case 'COOLER':
      return '쿨러';
    default:
      return category;
  }
}
