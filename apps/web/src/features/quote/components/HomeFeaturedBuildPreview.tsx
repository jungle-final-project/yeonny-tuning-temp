import type { KeyboardEvent, SyntheticEvent } from 'react';
import { Heart, ShoppingCart } from 'lucide-react';
import { partImageUrl } from '../../parts/partDisplay';
import type { PartRow } from '../../parts/types';
import type { BuildGraphResolveResponse, PartCategory } from '../aiSelection';
import { BuildDependencyGraph } from './BuildDependencyGraph';

type PreviewBuild = {
  id: string;
  name: string;
  tag: string;
  spec: string;
  summary: string;
  tone: string;
  partSearches: Array<{ category: PartCategory; searchQuery: string }>;
};

type PreviewResolvedPart = {
  search: { category: PartCategory; searchQuery: string };
  part: PartRow;
};

export type HomeFeaturedBuildPreviewItem = {
  build: PreviewBuild;
  buildParts: PreviewResolvedPart[];
  casePart?: PartRow;
  assetTotalPrice: number | null;
};

type HomeFeaturedBuildPreviewProps = {
  items: HomeFeaturedBuildPreviewItem[];
  selectedBuildId: string | null;
  applyingBuildId: string | null;
  graph?: BuildGraphResolveResponse;
  isGraphLoading: boolean;
  isGraphError: boolean;
  onSelectBuild: (buildId: string) => void;
  onClearSelection: () => void;
  onApplyBuild: (build: PreviewBuild, buildParts: PreviewResolvedPart[]) => void;
  onImageError: (event: SyntheticEvent<HTMLImageElement>) => void;
};

export function HomeFeaturedBuildPreview({
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
}: HomeFeaturedBuildPreviewProps) {
  const selectedItem = selectedBuildId ? items.find((item) => item.build.id === selectedBuildId) ?? null : null;
  const selectedGraphItems = selectedItem?.buildParts.map(({ search, part }) => ({
    partId: part.id,
    category: search.category,
    quantity: 1
  })) ?? [];
  const selectedGraphSignature = selectedGraphItems
    .map((item) => `${item.category}:${item.partId}:${item.quantity}`)
    .sort()
    .join('|');
  const isSelectedBuildComplete = Boolean(selectedItem && selectedGraphItems.length === selectedItem.build.partSearches.length);

  if (!selectedItem) {
    return (
      <div className="home-featured-picker-grid">
        {items.map((item) => (
          <FeaturedPreviewCard
            key={item.build.id}
            item={item}
            isSelected={false}
            isApplying={applyingBuildId === item.build.id}
            onSelect={() => onSelectBuild(item.build.id)}
            onApply={() => onApplyBuild(item.build, item.buildParts)}
            onImageError={onImageError}
          />
        ))}
      </div>
    );
  }

  return (
    <div className="home-featured-preview-layout">
      <div className="home-featured-selected-panel">
        <FeaturedPreviewCard
          item={selectedItem}
          isSelected
          isApplying={applyingBuildId === selectedItem.build.id}
          onSelect={() => undefined}
          onApply={() => onApplyBuild(selectedItem.build, selectedItem.buildParts)}
          onImageError={onImageError}
        />
        <button
          type="button"
          onClick={onClearSelection}
          className="home-featured-preview-reset rounded-md border border-commerce-line bg-white px-3 py-2 text-xs font-black text-slate-600 transition hover:border-commerce-ink hover:text-commerce-ink"
        >
          다른 추천상품 보기
        </button>
      </div>

      <div className="home-featured-preview-graph">
        <BuildDependencyGraph
          key={`${selectedItem.build.id}:${selectedGraphSignature}`}
          variant="preview"
          graph={graph}
          isLoading={isGraphLoading}
          isError={isGraphError}
          totalPrice={selectedItem?.assetTotalPrice ?? undefined}
          title="추천상품 관계도"
          subtitle={selectedItem ? `${selectedItem.build.name} 구성의 가격, 호환성, 의존성을 미리 확인합니다.` : '추천상품을 선택하면 관계도 미리보기가 표시됩니다.'}
          candidateContext={isSelectedBuildComplete ? {
            source: 'AI_BUILD',
            items: selectedGraphItems,
            readOnly: true,
            selectedPartIds: new Set(selectedGraphItems.map((item) => item.partId))
          } : undefined}
        />
      </div>
    </div>
  );
}

function FeaturedPreviewCard({
  item,
  isSelected,
  isApplying,
  onSelect,
  onApply,
  onImageError
}: {
  item: HomeFeaturedBuildPreviewItem;
  isSelected: boolean;
  isApplying: boolean;
  onSelect: () => void;
  onApply: () => void;
  onImageError: (event: SyntheticEvent<HTMLImageElement>) => void;
}) {
  const isComplete = item.buildParts.length === item.build.partSearches.length;

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    onSelect();
  }

  return (
    <article
      role="button"
      tabIndex={0}
      data-testid={'home-featured-preview-card-' + item.build.id}
      aria-label={item.build.name + ' 관계도 미리보기'}
      aria-pressed={isSelected}
      onClick={onSelect}
      onKeyDown={handleKeyDown}
      className={'home-featured-preview-card home-featured-interest-card group ' + (isSelected ? 'home-featured-preview-card--selected' : '')}
    >
      <div className="home-featured-preview-thumb-wrap">
        {item.casePart ? (
          <img
            src={partImageUrl(item.casePart)}
            alt={item.casePart.name + ' 제품 사진'}
            onError={onImageError}
            className="home-featured-preview-thumb"
          />
        ) : (
          <div className="home-featured-preview-thumb home-featured-preview-thumb--empty">
            준비 중
          </div>
        )}
      </div>

      <div className="home-featured-preview-body min-w-0">
        <div className="home-featured-preview-title truncate text-base font-semibold text-[#222222]">{item.build.name}</div>
        {item.assetTotalPrice !== null ? (
          <div className="home-featured-preview-price mt-1 truncate text-sm font-normal text-[#595959]">
            약 {Math.round(item.assetTotalPrice / 10_000).toLocaleString()}만원
          </div>
        ) : (
          <div className="home-featured-preview-price mt-1 text-sm font-normal text-[#777777]">가격 계산 중</div>
        )}

        {isSelected ? (
          <button
            type="button"
            aria-label={item.build.name + ' 셀프 견적에 담기'}
            onClick={(event) => {
              event.stopPropagation();
              onApply();
            }}
            disabled={!isComplete || isApplying}
            className="home-featured-preview-apply mt-3 inline-flex items-center gap-1 rounded-full bg-[#235df7] px-4 py-2 text-xs font-semibold text-white transition hover:bg-[#174ad1] disabled:cursor-wait disabled:bg-slate-300"
          >
            {isApplying ? <ShoppingCart size={12} /> : <Heart size={12} />}
            견적에 담기
          </button>
        ) : null}
      </div>
    </article>
  );
}
