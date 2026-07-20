import type { QuoteDraftItem } from '../../types';

type DraftQuantityStepperProps = {
  item: QuoteDraftItem;
  disabled: boolean;
  onChange: (partId: string, quantity: number) => void;
};

export function DraftQuantityStepper({ item, disabled, onChange }: DraftQuantityStepperProps) {
  return (
    <div className="flex h-7 overflow-hidden rounded border border-slate-300" aria-label={`${item.name} quantity selector`}>
      <button
        type="button"
        aria-label={`${item.name} quantity decrease`}
        data-testid={`draft-quantity-decrease-${item.partId}`}
        disabled={disabled}
        onClick={() => onChange(item.partId, item.quantity - 1)}
        className="w-7 bg-slate-50 text-sm font-bold text-slate-600 disabled:text-slate-300"
      >
        -
      </button>
      <div
        data-testid={`draft-quantity-value-${item.partId}`}
        className="flex w-8 items-center justify-center border-x border-slate-300 text-[11px] font-bold text-slate-900"
      >
        {item.quantity}
      </div>
      <button
        type="button"
        aria-label={`${item.name} quantity increase`}
        data-testid={`draft-quantity-increase-${item.partId}`}
        disabled={disabled || item.quantity >= 9}
        onClick={() => onChange(item.partId, item.quantity + 1)}
        className="w-7 bg-slate-50 text-sm font-bold text-slate-600 disabled:text-slate-300"
      >
        +
      </button>
    </div>
  );
}
