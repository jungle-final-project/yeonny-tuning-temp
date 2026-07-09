import type { CSSProperties, KeyboardEvent, SyntheticEvent } from 'react';
import { GitBranch, Heart, PackageCheck, ShoppingCart } from 'lucide-react';
import { partImageUrl } from '../../parts/partDisplay';
import type { PartRow } from '../../parts/types';
import type { AiRecommendedBuild, BuildGraphResolveResponse } from '../aiSelection';
import { BuildDependencyGraph } from './BuildDependencyGraph';

export type HomeAiBuildPreviewItem = {
  build: AiRecommendedBuild;
  imagePart?: PartRow | null;
};

type HomeAiBuildPreviewProps = {
  items: HomeAiBuildPreviewItem[];
  selectedBuildId: string | null;
  applyingBuildId: string | null;
  graph?: BuildGraphResolveResponse;
  isGraphLoading: boolean;
  isGraphError: boolean;
  onSelectBuild: (buildId: string) => void;
  onClearSelection: () => void;
  onApplyBuild: (build: AiRecommendedBuild) => void;
  onImageError: (event: SyntheticEvent<HTMLImageElement>) => void;
};

export function HomeAiBuildPreview({
  items,
  selectedBuildId,
  applyingBuildId,
  graph,
  isGraphLoading,
  isGraphError,
  onSelectBuild,
  onClearSelection,
  onApplyBuild,
  onImageError
}: HomeAiBuildPreviewProps) {
  const selectedItem = selectedBuildId ? items.find((item) => item.build.id === selectedBuildId) ?? null : null;
  const selectedGraphItems = selectedItem?.build.items.map((item) => ({
    partId: item.partId,
    category: item.category,
    quantity: item.quantity
  })) ?? [];
  const selectedGraphSignature = selectedGraphItems
    .map((item) => `${item.category}:${item.partId}:${item.quantity}`)
    .sort()
    .join('|');
  const columnCount = Math.max(1, Math.min(items.length, 3));
  const rowCount = Math.max(1, Math.ceil(items.length / columnCount));
  const gridStyle = {
    '--home-ai-grid-columns': columnCount,
    '--home-ai-grid-rows': rowCount
  } as CSSProperties;

  if (!selectedItem) {
    return (
      <div className="home-featured-picker-grid home-ai-picker-grid" style={gridStyle}>
        {items.map((item) => (
          <AiPreviewCard
            key={item.build.id}
            item={item}
            isSelected={false}
            isApplying={applyingBuildId === item.build.id}
            onSelect={() => onSelectBuild(item.build.id)}
            onApply={() => onApplyBuild(item.build)}
            onImageError={onImageError}
          />
        ))}
      </div>
    );
  }

  return (
    <div className="home-featured-preview-layout home-ai-preview-layout">
      <div className="home-featured-selected-panel home-ai-selected-panel">
        <AiPreviewCard
          item={selectedItem}
          isSelected
          isApplying={applyingBuildId === selectedItem.build.id}
          onSelect={() => undefined}
          onApply={() => onApplyBuild(selectedItem.build)}
          onImageError={onImageError}
        />
        <button
          type="button"
          onClick={onClearSelection}
          className="home-featured-preview-reset rounded-md border border-commerce-line bg-white px-3 py-2 text-xs font-black text-slate-600 transition hover:border-commerce-ink hover:text-commerce-ink"
        >
          다른 AI 추천상품 보기
        </button>
      </div>

      <div className="home-featured-preview-graph home-ai-preview-graph">
        <BuildDependencyGraph
          key={`${selectedItem.build.id}:${selectedGraphSignature}`}
          variant="preview"
          graph={graph}
          isLoading={isGraphLoading}
          isError={isGraphError}
          totalPrice={selectedItem.build.totalPrice}
          title="AI 추천 관계도"
          subtitle={`${selectedItem.build.title} 구성의 부품 의존성과 호환성 흐름을 미리 확인합니다.`}
          candidateContext={{
            source: 'AI_BUILD',
            items: selectedGraphItems,
            readOnly: true,
            selectedPartIds: new Set(selectedGraphItems.map((item) => item.partId))
          }}
        />
      </div>
    </div>
  );
}

function AiPreviewCard({
  item,
  isSelected,
  isApplying,
  onSelect,
  onApply,
  onImageError
}: {
  item: HomeAiBuildPreviewItem;
  isSelected: boolean;
  isApplying: boolean;
  onSelect: () => void;
  onApply: () => void;
  onImageError: (event: SyntheticEvent<HTMLImageElement>) => void;
}) {
  const { build, imagePart } = item;
  const hasWarnings = Boolean(build.warnings && build.warnings.length > 0);

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    onSelect();
  }

  return (
    <article
      role="button"
      tabIndex={0}
      data-testid={`home-ai-preview-card-${build.id}`}
      aria-label={`${build.title} 관계도 미리보기`}
      aria-pressed={isSelected}
      onClick={onSelect}
      onKeyDown={handleKeyDown}
      className={`home-featured-preview-card home-ai-build-card home-ai-preview-card group bg-gradient-to-br ${aiBuildTone(build)} ${isSelected ? 'home-featured-preview-card--selected' : ''}`}
    >
      <div className="home-featured-preview-thumb-wrap home-ai-preview-thumb-wrap">
        {imagePart ? (
          <img
            src={partImageUrl(imagePart)}
            alt={`${imagePart.name} 상품 사진`}
            onError={onImageError}
            className="home-featured-preview-thumb home-ai-preview-thumb"
          />
        ) : (
          <div className="home-featured-preview-thumb home-featured-preview-thumb--empty">
            준비중
          </div>
        )}
      </div>

      <div className="home-featured-preview-body min-w-0 flex-1">
        <div className="home-featured-preview-heading flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="home-featured-preview-title truncate text-sm font-black text-commerce-ink">{build.title}</div>
            <div className="home-featured-preview-spec truncate text-xs font-bold text-slate-500">{build.budgetLabel ?? build.tierLabel}</div>
          </div>
          <span className={`home-featured-preview-tag shrink-0 rounded px-1.5 py-0.5 text-[10px] font-black ${hasWarnings ? 'bg-amber-100 text-amber-700' : 'bg-commerce-sale text-white'}`}>
            {isApplying ? '적용 중' : hasWarnings ? '검증 확인' : 'AI 추천'}
          </span>
        </div>

        <div className="home-featured-preview-actions mt-2 flex items-end justify-between gap-2">
          <div className="min-w-0">
            <div className="home-featured-preview-price truncate text-sm font-black text-commerce-sale">{build.totalPrice.toLocaleString()}원</div>
            <div className={`home-featured-preview-status flex items-center gap-1 text-[11px] font-black ${hasWarnings ? 'text-amber-600' : 'text-brand-blue'}`}>
              {hasWarnings ? <PackageCheck size={12} /> : <GitBranch size={12} />}
              {isSelected ? '관계도 표시 중' : hasWarnings ? '검증 확인 필요' : '관계도 미리보기'}
            </div>
          </div>

          <button
            type="button"
            aria-label={`${build.title} 셀프 견적에 담기`}
            onClick={(event) => {
              event.stopPropagation();
              onApply();
            }}
            disabled={isApplying}
            className="home-featured-preview-apply inline-flex shrink-0 items-center gap-1 rounded-md bg-commerce-ink px-2.5 py-1.5 text-[11px] font-black text-white transition hover:bg-brand-blue disabled:cursor-wait disabled:bg-slate-300"
          >
            {isApplying ? <ShoppingCart size={12} /> : <Heart size={12} />}
            담기
          </button>
        </div>
      </div>
    </article>
  );
}

function aiBuildTone(build: AiRecommendedBuild) {
  if (build.warnings && build.warnings.length > 0) {
    return 'from-amber-50 via-white to-white';
  }
  if (build.tier === 'performance') {
    return 'from-indigo-50 via-white to-white';
  }
  if (build.tier === 'budget') {
    return 'from-emerald-50 via-white to-white';
  }
  return 'from-blue-50 via-white to-white';
}
