import { useQuery } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { useState } from 'react';
import { openAiAssistant } from '../../../../lib/events';
import { PART_CATEGORY_LABELS, type PartCategory } from '../../../quote/aiSelection';
import { listParts } from '../../partsApi';
import type { PartRow, QuoteDraftItem } from '../../types';

// 멘토 피드백 R1: "요즘 게임이 잘 안 돌아가요" 같은 증상에서 출발하는 업그레이드 흐름.
// 챗봇 의도는 넓히지 않는다(팀 결정: 3기능 축소 유지) — 증상을 병목 카테고리로 좁히고,
// 현재 장착 부품보다 상위 후보(최소 변경/고성능)를 골라 기존 교체 시뮬레이션 질문으로
// 변환해 챗봇에 전달한다. 백엔드 무변경.

type Symptom = {
  key: string;
  label: string;
  category: PartCategory;
  hint: string;
};

const SYMPTOMS: Symptom[] = [
  { key: 'frame', label: '게임 프레임이 낮아요', category: 'GPU', hint: '그래픽카드가 병목일 가능성이 높아요' },
  { key: 'compute', label: '작업·로딩이 느려요', category: 'CPU', hint: 'CPU 성능을 올리면 체감이 커요' },
  { key: 'multitask', label: '멀티태스킹이 버벅여요', category: 'RAM', hint: '메모리 용량·속도를 올려보세요' },
  { key: 'space', label: '저장 공간이 부족해요', category: 'STORAGE', hint: '더 큰 SSD로 교체하거나 추가하세요' }
];

// 시뮬레이션 질문의 조사(로/으로) — 받침 유무로 고른다. 영문/숫자로 끝나면 '로'.
function replaceParticle(name: string) {
  const lastChar = name.trim().slice(-1);
  const code = lastChar.charCodeAt(0);
  if (code >= 0xac00 && code <= 0xd7a3) {
    return (code - 0xac00) % 28 !== 0 ? '으로' : '로';
  }
  return lastChar === 'ㄹ' ? '로' : '로';
}

async function upgradeCandidates(category: PartCategory, currentPrice: number) {
  const [minimal, performance] = await Promise.all([
    listParts({ category, minPrice: currentPrice + 1, sort: 'price_asc', size: 1 }),
    listParts({ category, minPrice: Math.ceil(currentPrice * 1.6), sort: 'price_asc', size: 1 })
  ]);
  const minimalPart: PartRow | null = minimal.items[0] ?? null;
  let performancePart: PartRow | null = performance.items[0] ?? null;
  if (minimalPart && performancePart && minimalPart.id === performancePart.id) {
    performancePart = null;
  }
  return { minimalPart, performancePart };
}

export function UpgradeAdvisorPanel({
  draftItems,
  onOpenSlot,
  onClose
}: {
  draftItems: QuoteDraftItem[];
  onOpenSlot: (category: PartCategory) => void;
  onClose: () => void;
}) {
  const [symptomKey, setSymptomKey] = useState<string | null>(null);
  const symptom = SYMPTOMS.find((item) => item.key === symptomKey) ?? null;
  const currentItem = symptom ? draftItems.find((item) => item.category === symptom.category) ?? null : null;

  const candidatesQuery = useQuery({
    queryKey: ['upgrade-advisor', symptom?.category, currentItem?.partId, currentItem?.currentPrice],
    queryFn: () => upgradeCandidates(symptom!.category, currentItem!.currentPrice),
    enabled: Boolean(symptom && currentItem)
  });

  const askSimulation = (part: PartRow) => {
    // 기존 교체 시뮬레이션 의도("…로 바꾸면?")로 변환해 챗봇에 자동 전송한다.
    openAiAssistant({ prefill: `${part.name}${replaceParticle(part.name)} 바꾸면 어때?`, autoSubmit: true });
  };

  const optionCard = (title: string, description: string, part: PartRow | null, testId: string) => {
    if (!part) {
      return null;
    }
    const priceDelta = currentItem ? part.price - currentItem.currentPrice : 0;
    return (
      <button
        type="button"
        data-testid={testId}
        onClick={() => askSimulation(part)}
        className="flex min-w-0 flex-1 flex-col rounded-lg border border-commerce-line bg-white p-3 text-left transition hover:border-brand-blue hover:bg-blue-50/40"
      >
        <span className="text-[11px] font-black text-brand-blue">{title}</span>
        <span className="mt-1 truncate text-xs font-black text-commerce-ink">{part.name}</span>
        <span className="mt-1 text-[11px] font-bold text-slate-500">
          {part.price.toLocaleString()}원
          {priceDelta > 0 ? <span className="ml-1 text-commerce-sale">(+{priceDelta.toLocaleString()}원)</span> : null}
        </span>
        <span className="mt-2 text-[10px] font-bold text-slate-400">{description}</span>
      </button>
    );
  };

  return (
    <section data-testid="upgrade-advisor-panel" className="panel border-blue-100 p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="text-sm font-black text-commerce-ink">업그레이드 진단</h2>
          <p className="mt-0.5 text-[11px] font-bold text-slate-500">
            불편한 증상을 고르면 병목 부품의 교체 후보를 찾아 AI 성능 비교로 이어드려요
          </p>
        </div>
        <button
          type="button"
          data-testid="upgrade-advisor-close"
          onClick={onClose}
          aria-label="업그레이드 진단 닫기"
          className="rounded-md border border-commerce-line bg-white p-1.5 text-slate-500 transition hover:border-commerce-ink hover:text-commerce-ink"
        >
          <X size={14} />
        </button>
      </div>

      <div className="flex flex-wrap gap-2">
        {SYMPTOMS.map((item) => (
          <button
            key={item.key}
            type="button"
            data-testid={`upgrade-symptom-${item.key}`}
            data-selected={symptomKey === item.key ? 'true' : 'false'}
            onClick={() => setSymptomKey(item.key)}
            className={`rounded-full border px-3 py-1.5 text-xs font-black transition ${
              symptomKey === item.key
                ? 'border-brand-blue bg-blue-50 text-brand-blue'
                : 'border-commerce-line bg-white text-slate-600 hover:border-commerce-ink'
            }`}
          >
            {item.label}
          </button>
        ))}
      </div>

      {symptom ? (
        <div className="mt-3 border-t border-commerce-line pt-3">
          {!currentItem ? (
            <div className="flex flex-wrap items-center gap-2 text-xs font-bold text-slate-600">
              <span>
                진단하려면 먼저 {PART_CATEGORY_LABELS[symptom.category]}를 견적에 담아 주세요 — 현재 부품 기준으로 상위 후보를 찾아드려요.
              </span>
              <button
                type="button"
                data-testid="upgrade-open-slot"
                onClick={() => onOpenSlot(symptom.category)}
                className="rounded border border-brand-blue/30 bg-white px-2 py-1 text-[11px] font-black text-brand-blue hover:bg-blue-50"
              >
                {PART_CATEGORY_LABELS[symptom.category]} 후보 열기
              </button>
            </div>
          ) : candidatesQuery.isLoading ? (
            <div className="text-xs font-bold text-slate-400">상위 후보를 찾는 중…</div>
          ) : (
            <>
              <div className="mb-2 text-[11px] font-bold text-slate-500">
                {symptom.hint} · 현재: <span className="text-slate-700">{currentItem.name}</span> ({currentItem.currentPrice.toLocaleString()}원)
              </div>
              {candidatesQuery.data?.minimalPart || candidatesQuery.data?.performancePart ? (
                <div className="flex flex-col gap-2 sm:flex-row">
                  {optionCard('최소 변경', '가격 부담을 줄이면서 한 단계 올립니다 — 누르면 AI가 성능 변화를 비교해요', candidatesQuery.data?.minimalPart ?? null, 'upgrade-option-minimal')}
                  {optionCard('고성능', '확실한 체감을 원할 때 — 누르면 AI가 성능 변화를 비교해요', candidatesQuery.data?.performancePart ?? null, 'upgrade-option-performance')}
                </div>
              ) : (
                <div className="text-xs font-bold text-slate-500">
                  현재 {PART_CATEGORY_LABELS[symptom.category]}가 이미 최상위권이에요 — 다른 증상을 골라 보세요.
                </div>
              )}
            </>
          )}
        </div>
      ) : null}
    </section>
  );
}
