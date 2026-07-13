import { useQuery } from '@tanstack/react-query';
import { Briefcase, Check, Code, Gamepad2, Plus, Video, type LucideIcon } from 'lucide-react';
import { getToken } from '../../../lib/api';
import { openAiAssistant } from '../../../lib/events';
import { getCurrentQuoteDraft } from '../../parts/partsApi';
import type { QuoteDraft } from '../../parts/types';
import type { BuildGraphResolveResponse } from '../aiSelection';
import { resolveBuildGraph } from '../quoteApi';

type UsagePreset = {
  id: string;
  label: string;
  sub: string;
  prompt: string;
  Icon: LucideIcon;
};

const USAGE_PRESETS: UsagePreset[] = [
  {
    id: 'gaming',
    label: '게임용',
    sub: 'QHD·144fps 기준',
    prompt: 'QHD 해상도에서 144fps로 게임할 수 있는 PC 추천해줘',
    Icon: Gamepad2
  },
  {
    id: 'video',
    label: '영상 편집',
    sub: '렌더링·저장공간 우선',
    prompt: '영상 편집과 렌더링 작업에 맞는 PC 추천해줘, 저장공간도 넉넉하게',
    Icon: Video
  },
  {
    id: 'dev',
    label: '개발·AI',
    sub: '멀티코어·메모리 우선',
    prompt: '개발과 AI 작업에 맞는 PC 추천해줘, 멀티코어와 메모리 여유를 우선해줘',
    Icon: Code
  },
  {
    id: 'office',
    label: '사무·일반',
    sub: '가성비·저전력 우선',
    prompt: '사무·일반 용도로 쓸 가성비 좋은 저전력 PC 추천해줘',
    Icon: Briefcase
  }
];

const SUPPORTED_CATEGORIES = new Set(['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER']);
const QUOTE_SLOTS = [
  { category: 'CPU', label: 'CPU', image: '/slot-board/parts/cpu.svg' },
  { category: 'MOTHERBOARD', label: '메인보드', image: '/slot-board/parts/motherboard.svg' },
  { category: 'RAM', label: '메모리', image: '/slot-board/parts/ram.svg' },
  { category: 'GPU', label: 'GPU', image: '/slot-board/parts/gpu.svg' },
  { category: 'STORAGE', label: '저장장치', image: '/slot-board/parts/storage.svg' },
  { category: 'PSU', label: '파워', image: '/slot-board/parts/psu.svg' },
  { category: 'CASE', label: '케이스', image: '/slot-board/parts/case.svg' },
  { category: 'COOLER', label: '쿨러', image: '/slot-board/parts/cooler.svg' }
] as const;

function chooseUsagePreset(preset: UsagePreset) {
  openAiAssistant({ placement: 'center', prefill: preset.prompt, autoSubmit: true });
}

export function HomeQuickStartPanel() {
  const hasToken = Boolean(getToken());
  const quoteDraftQuery = useQuery({
    queryKey: ['quote-draft', 'current'],
    queryFn: getCurrentQuoteDraft,
    enabled: hasToken
  });
  const hasDraftItems = Boolean(quoteDraftQuery.data?.items.length);
  const graphQuery = useQuery({
    queryKey: [
      'build-graph',
      'home-quote-summary',
      quoteDraftQuery.data?.updatedAt ?? null,
      quoteDraftQuery.data?.items.map((item) => [item.partId, item.quantity]) ?? []
    ],
    queryFn: () => resolveBuildGraph({ source: 'QUOTE_DRAFT_CURRENT' }),
    enabled: hasToken && hasDraftItems
  });

  return (
    <section
      data-testid="home-quick-start-panel"
      aria-labelledby="home-quick-start-title"
      className="flex h-full min-h-[560px] w-full flex-col overflow-hidden rounded-xl border border-slate-200 bg-[#f8fbff] p-5"
    >
      {quoteDraftQuery.isLoading ? (
        <QuickStartLoading />
      ) : hasDraftItems ? (
        <HomeQuoteSummaryPanel
          draft={quoteDraftQuery.data}
          graph={graphQuery.data}
          isGraphLoading={graphQuery.isLoading}
          hasDraftError={quoteDraftQuery.isError}
          hasGraphError={graphQuery.isError}
        />
      ) : (
        <QuickStartPresets />
      )}
    </section>
  );
}

function QuickStartLoading() {
  return (
    <div className="flex flex-1 flex-col justify-center gap-2" aria-busy="true">
      <div className="h-4 w-32 animate-pulse rounded bg-slate-200" />
      <div className="h-3 w-48 animate-pulse rounded bg-slate-200" />
    </div>
  );
}

function QuickStartPresets() {
  return (
    <>
      <h2 id="home-quick-start-title" className="text-base font-black text-commerce-ink">어떤 용도로 맞출까요?</h2>
      <p className="mt-1 text-sm font-semibold text-slate-600">용도를 고르면 예산대별 조합을 바로 보여드려요</p>

      <div className="mt-5 flex flex-col gap-2">
        {USAGE_PRESETS.map((preset) => (
          <button
            key={preset.id}
            type="button"
            onClick={() => chooseUsagePreset(preset)}
            className="group flex items-center gap-3 rounded-lg border border-slate-200 bg-white px-4 py-3 text-left transition hover:border-brand-blue hover:bg-blue-50 focus:outline-none focus:ring-4 focus:ring-blue-100"
          >
            <span className="grid h-10 w-10 shrink-0 place-items-center rounded-lg bg-slate-100 text-slate-600 transition group-hover:bg-brand-blue group-hover:text-white">
              <preset.Icon size={20} aria-hidden="true" />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block text-sm font-black text-commerce-ink">{preset.label}</span>
              <span className="mt-0.5 block text-xs font-semibold text-slate-500">{preset.sub}</span>
            </span>
          </button>
        ))}
      </div>

      <div className="mt-auto pt-5 text-center text-xs font-bold text-slate-500">
        원하는 용도가 없다면{' '}
        <button
          type="button"
          onClick={() => openAiAssistant({ placement: 'center' })}
          className="font-black text-brand-blue hover:underline focus:outline-none"
        >
          AI에게 직접 말해보세요
        </button>
      </div>
    </>
  );
}

function HomeQuoteSummaryPanel({
  draft,
  graph,
  isGraphLoading,
  hasDraftError,
  hasGraphError
}: {
  draft?: QuoteDraft;
  graph?: BuildGraphResolveResponse;
  isGraphLoading: boolean;
  hasDraftError: boolean;
  hasGraphError: boolean;
}) {
  if (hasDraftError || !draft) {
    return (
      <div className="flex flex-1 flex-col justify-center gap-2" role="status">
        <div className="text-sm font-black text-amber-900">현재 견적 요약을 불러오지 못했습니다.</div>
        <p className="text-xs font-semibold text-slate-600">잠시 후 다시 시도하거나 셀프 견적 화면에서 확인해 주세요.</p>
      </div>
    );
  }

  const categoryCount = new Set(
    draft.items
      .map((item) => item.category.toUpperCase())
      .filter((category) => SUPPORTED_CATEGORIES.has(category))
  ).size;
  const selectedCategories = new Set(draft.items.map((item) => item.category.toUpperCase()));
  const status = hasGraphError ? 'WARN' : graph ? highestGraphStatus(graph) : null;
  const insight = hasGraphError
    ? '검증 상태를 불러오지 못했습니다. 견적 화면에서 다시 확인해 주세요.'
    : graph
      ? highestGraphInsight(graph, status ?? 'PASS')
      : '현재 구성의 호환성과 전력 여유를 확인하고 있습니다.';
  const statusClassName = status === 'FAIL'
    ? 'border-red-200 bg-red-50 text-red-800'
    : status === 'WARN'
      ? 'border-amber-200 bg-amber-50 text-amber-900'
      : 'border-emerald-200 bg-emerald-50 text-emerald-800';

  return (
    <div data-testid="home-quote-summary-panel" className="flex flex-1 flex-col">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 id="home-quick-start-title" className="text-base font-black text-commerce-ink">내 견적</h2>
        </div>
        {status ? (
          <span
            data-status={status}
            aria-label={`검증 상태 ${status}`}
            className={`inline-flex shrink-0 items-center gap-1 rounded-md border px-2 py-1 text-[11px] font-black ${statusClassName}`}
          >
            {status === 'PASS' ? <Check size={13} aria-hidden="true" /> : null}
            {status === 'FAIL' ? '적용 불가' : status === 'WARN' ? '주의 확인' : '검증 완료'}
          </span>
        ) : (
          <span className="shrink-0 rounded-md border border-blue-200 bg-white px-2 py-1 text-[11px] font-black text-blue-800">
            {isGraphLoading ? '검증 중' : '확인 대기'}
          </span>
        )}
      </div>

      <div className="mt-4 rounded-lg border border-blue-100 bg-blue-50 px-4 py-3.5">
        <div className="flex items-center justify-between gap-3">
          <div className="text-xs font-black text-blue-950">부품 {categoryCount} / 8 담김</div>
          <div className="text-xl font-black tabular-nums text-brand-blue">{formatWon(draft.totalPrice)}</div>
        </div>
        <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-blue-100" aria-label={`견적 완성도 ${categoryCount} / 8`}>
          <div className="h-full rounded-full bg-brand-blue" style={{ width: `${(categoryCount / 8) * 100}%` }} />
        </div>
        <p className="sr-only">{insight}</p>
      </div>

        <div className="mt-4 grid flex-1 grid-cols-2 grid-rows-4 gap-2">
          {QUOTE_SLOTS.map((slot) => {
            const selected = selectedCategories.has(slot.category);
            return (
              <div
                key={slot.category}
                data-testid={`home-quote-slot-${slot.category.toLowerCase()}`}
                className={`relative grid min-h-0 grid-rows-[1fr_auto] place-items-center overflow-hidden rounded-lg border px-3 pb-2 pt-3 ${selected ? 'border-slate-200 bg-white' : 'border-dashed border-slate-200 bg-slate-50/80'}`}
              >
                <span className={`absolute right-2 top-2 grid h-4 w-4 place-items-center rounded-full ${selected ? 'bg-emerald-500 text-white' : 'bg-blue-100 text-blue-700'}`}>
                  {selected ? <Check size={11} strokeWidth={3} aria-hidden="true" /> : <Plus size={10} aria-hidden="true" />}
                </span>
                <img
                  src={slot.image}
                  alt=""
                  className={`max-h-[46px] w-full object-contain transition-opacity ${selected ? 'opacity-100' : 'opacity-35 grayscale'}`}
                />
                <span className="mt-1 text-[11px] font-black leading-none text-commerce-ink">{slot.label}</span>
                <span className="sr-only">{selected ? ' 선택됨' : ' 미선택'}</span>
              </div>
            );
          })}
      </div>

      <div className="mt-4 flex flex-col gap-2">
        <a
          href="/self-quote"
          className="inline-flex min-h-11 items-center justify-center rounded-lg bg-commerce-ink px-4 text-sm font-black text-white transition hover:bg-brand-blue focus:outline-none focus:ring-4 focus:ring-blue-100"
        >
          셀프 견적에서 계속하기
        </a>
      </div>

    </div>
  );
}

function highestGraphStatus(graph: BuildGraphResolveResponse): 'PASS' | 'WARN' | 'FAIL' {
  const statuses = [
    ...graph.toolResults.map((result) => result.status),
    ...graph.insights.map((insight) => insight.status)
  ];
  if (statuses.includes('FAIL')) return 'FAIL';
  if (statuses.includes('WARN')) return 'WARN';
  return 'PASS';
}

function highestGraphInsight(graph: BuildGraphResolveResponse, status: 'PASS' | 'WARN' | 'FAIL') {
  const insight = graph.insights.find((item) => item.status === status)
    ?? graph.insights.find((item) => item.status === 'FAIL')
    ?? graph.insights.find((item) => item.status === 'WARN')
    ?? graph.insights[0];
  if (insight) return `${insight.title} · ${insight.description}`;
  const toolResult = graph.toolResults.find((item) => item.status === status) ?? graph.toolResults[0];
  return toolResult?.summary ?? graph.summary;
}

function formatWon(value: number) {
  return `${new Intl.NumberFormat('ko-KR').format(value)}원`;
}
