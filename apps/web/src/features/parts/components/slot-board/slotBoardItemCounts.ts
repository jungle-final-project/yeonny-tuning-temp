import type { QuoteDraftItem } from '../../types';

// Kit products such as "32GB(16Gx2)" occupy multiple RAM sticks.
export function itemStickCount(item: QuoteDraftItem): number {
  const moduleCount = Number(item.attributes?.moduleCount);
  return item.quantity * (Number.isFinite(moduleCount) && moduleCount >= 1 ? moduleCount : 1);
}
