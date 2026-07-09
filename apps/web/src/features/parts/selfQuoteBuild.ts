import type { AiRecommendedBuild, PartCategory } from '../quote/aiSelection';
import { isSlotCategory } from './components/slot-board/slotBoardConfig';
import type { QuoteDraft, QuoteDraftItem } from './types';

export function selfQuoteBuildId(draft: QuoteDraft) {
  return `self-quote-${draft.id ?? 'current'}`;
}

export function quoteDraftToRecommendedBuild(draft: QuoteDraft): AiRecommendedBuild {
  const items = draft.items
    .filter((item): item is QuoteDraftItem & { category: PartCategory } => isSlotCategory(item.category))
    .map((item) => ({
      partId: item.partId,
      category: item.category,
      name: item.name,
      manufacturer: item.manufacturer ?? 'BuildGraph',
      quantity: item.quantity,
      price: item.currentPrice,
      note: '셀프 견적 장바구니에서 저장'
    }));
  const categories = Array.from(new Set(items.map((item) => item.category)));

  return {
    id: selfQuoteBuildId(draft),
    tier: 'balanced',
    label: '셀프',
    title: '셀프 견적 저장 조합',
    summary: '셀프 견적 페이지에서 선택한 부품을 내 견적함에 저장했습니다.',
    totalPrice: draft.totalPrice,
    badges: ['셀프 견적', `${items.length}개 부품`],
    budgetWon: draft.totalPrice,
    budgetLabel: '셀프 견적',
    tierLabel: '셀프 견적',
    appliedPartCategories: categories,
    items,
    toolResults: [],
    warnings: [],
    confidence: 'HIGH'
  };
}
