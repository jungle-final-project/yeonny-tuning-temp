import { X } from 'lucide-react';
import type { AiBuildItem, AiRecommendedBuild } from '../../../quote/aiSelection';
import { PART_CATEGORY_LABELS } from '../../../quote/aiSelection';
import type { QuoteDraftItem } from '../../types';

// 멘토 피드백 R1: AI 추천 3안(가성비/균형/고성능)을 나란히 놓고 현재 견적 대비
// 달라지는 부품을 표시한다. 데이터는 챗봇 응답이 sessionStorage에 남긴 최근 추천 배치
// (recentBuildsForChatContext) — 새 API 없이 기존 일괄 적용(apply-ai-build)만 재사용한다.

const TIER_ORDER: Record<string, number> = { budget: 0, balanced: 1, performance: 2 };

type DiffStatus = 'same' | 'changed' | 'added';

const DIFF_LABELS: Record<DiffStatus, string> = {
  same: '동일',
  changed: '교체됨',
  added: '추가됨'
};

const DIFF_CLASSES: Record<DiffStatus, string> = {
  same: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  changed: 'border-amber-200 bg-amber-50 text-amber-700',
  added: 'border-slate-200 bg-slate-100 text-slate-500'
};

function diffStatusFor(item: AiBuildItem, draftItems: QuoteDraftItem[]): DiffStatus {
  const sameCategory = draftItems.filter((draftItem) => draftItem.category === item.category);
  if (sameCategory.some((draftItem) => draftItem.partId === item.partId)) {
    return 'same';
  }
  return sameCategory.length > 0 ? 'changed' : 'added';
}

// 이 안이 현재 견적과 부품·수량까지 같은 구성인지 — '현재 적용됨' 표시와 적용 버튼 비활성 판단.
function isCurrentComposition(build: AiRecommendedBuild, draftItems: QuoteDraftItem[]) {
  if (draftItems.length === 0 || build.items.length === 0) {
    return false;
  }
  const key = (category: string, partId: string, quantity: number) => `${category}:${partId}:${quantity}`;
  const buildKeys = build.items.map((item) => key(item.category, item.partId, item.quantity)).sort().join('|');
  const draftKeys = draftItems.map((item) => key(item.category, item.partId, item.quantity)).sort().join('|');
  return buildKeys === draftKeys;
}

export function QuoteComparePanel({
  builds,
  draftItems,
  onApply,
  applyingBuildId,
  applyError,
  onClose
}: {
  builds: AiRecommendedBuild[];
  draftItems: QuoteDraftItem[];
  onApply: (build: AiRecommendedBuild) => void;
  applyingBuildId: string | null;
  applyError: string | null;
  onClose: () => void;
}) {
  const orderedBuilds = [...builds].sort((left, right) => (TIER_ORDER[left.tier] ?? 9) - (TIER_ORDER[right.tier] ?? 9));
  const columnsClass = orderedBuilds.length >= 3 ? 'md:grid-cols-3' : orderedBuilds.length === 2 ? 'md:grid-cols-2' : '';

  return (
    <section data-testid="quote-compare-panel" className="panel border-blue-100 p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="text-sm font-black text-commerce-ink">AI 추천 {orderedBuilds.length}안 비교</h2>
          <p className="mt-0.5 text-[11px] font-bold text-slate-500">
            현재 견적과 비교해 달라지는 부품을 표시합니다 — 동일(초록) · 교체됨(노랑) · 추가됨(회색)
          </p>
        </div>
        <button
          type="button"
          data-testid="quote-compare-close"
          onClick={onClose}
          aria-label="견적 비교 닫기"
          className="rounded-md border border-commerce-line bg-white p-1.5 text-slate-500 transition hover:border-commerce-ink hover:text-commerce-ink"
        >
          <X size={14} />
        </button>
      </div>

      {applyError ? (
        <div className="mb-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs font-bold text-red-700">{applyError}</div>
      ) : null}

      <div className={`grid gap-3 ${columnsClass}`}>
        {orderedBuilds.map((build) => {
          const isCurrent = isCurrentComposition(build, draftItems);
          const changedCount = build.items.filter((item) => diffStatusFor(item, draftItems) !== 'same').length;
          return (
            <div
              key={build.id}
              data-testid={`quote-compare-column-${build.tier}`}
              data-tier={build.tier}
              data-current={isCurrent ? 'true' : 'false'}
              className={`flex flex-col rounded-lg border p-3 ${isCurrent ? 'border-brand-blue bg-blue-50/40 ring-2 ring-blue-100' : 'border-commerce-line bg-white'}`}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="rounded-full bg-brand-blue/10 px-2 py-1 text-[11px] font-black text-brand-blue">{build.tierLabel}</span>
                {isCurrent ? (
                  <span className="rounded border border-emerald-200 bg-emerald-50 px-1.5 py-0.5 text-[10px] font-black text-emerald-700">현재 적용됨</span>
                ) : (
                  <span className="text-[10px] font-bold text-slate-400">변경 {changedCount}개</span>
                )}
              </div>
              <div className="mt-2 min-w-0">
                <div className="truncate text-xs font-black text-commerce-ink">{build.title}</div>
                <div data-testid={`quote-compare-total-${build.tier}`} className="mt-1 text-base font-black text-commerce-sale">
                  {build.totalPrice.toLocaleString()}원
                </div>
              </div>
              <ul className="mt-2 flex-1 space-y-1">
                {build.items.map((item) => {
                  const diff = diffStatusFor(item, draftItems);
                  return (
                    <li
                      key={`${build.id}-${item.partId}`}
                      data-diff={diff}
                      className="flex items-center justify-between gap-2 rounded border border-slate-100 bg-slate-50/50 px-2 py-1.5"
                    >
                      <span className="min-w-0 flex-1">
                        <span className="block text-[10px] font-bold text-slate-400">{PART_CATEGORY_LABELS[item.category] ?? item.category}</span>
                        <span className="block truncate text-[11px] font-bold text-slate-700">
                          {item.name}
                          {item.quantity > 1 ? ` ×${item.quantity}` : ''}
                        </span>
                      </span>
                      <span className={`shrink-0 rounded border px-1.5 py-0.5 text-[10px] font-black ${DIFF_CLASSES[diff]}`}>{DIFF_LABELS[diff]}</span>
                    </li>
                  );
                })}
              </ul>
              <button
                type="button"
                data-testid={`quote-compare-apply-${build.tier}`}
                onClick={() => onApply(build)}
                disabled={isCurrent || applyingBuildId !== null}
                className={`mt-3 rounded-md px-3 py-2 text-xs font-black transition disabled:cursor-not-allowed ${
                  isCurrent
                    ? 'border border-emerald-200 bg-emerald-50 text-emerald-700'
                    : 'bg-brand-blue text-white hover:bg-blue-700 disabled:opacity-60'
                }`}
              >
                {isCurrent ? '현재 적용됨' : applyingBuildId === build.id ? '적용 중…' : '이 안으로 적용'}
              </button>
            </div>
          );
        })}
      </div>
    </section>
  );
}
