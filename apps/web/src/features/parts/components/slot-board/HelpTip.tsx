import { HelpCircle } from 'lucide-react';

// 기능 이름 옆 ? 아이콘 — hover/focus 시 짙은 회색 말풍선으로 기능을 설명한다.
// 보드 하단 버튼은 placement="top", 화면 상단 패널은 placement="bottom"으로 쓴다.
export function HelpTip({
  label,
  text,
  placement = 'bottom',
  align = 'left'
}: {
  label: string;
  text: string;
  placement?: 'top' | 'bottom';
  align?: 'left' | 'right';
}) {
  const placementClass = placement === 'top' ? 'bottom-full mb-2' : 'top-full mt-2';
  const alignClass = align === 'right' ? 'right-0' : 'left-0';

  return (
    <span className="group relative inline-flex shrink-0">
      <button
        type="button"
        aria-label={label}
        className="grid h-5 w-5 place-items-center rounded-full text-slate-400 transition hover:text-slate-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-200"
      >
        <HelpCircle size={13} aria-hidden="true" />
      </button>
      <span
        role="tooltip"
        className={`pointer-events-none absolute z-50 w-60 rounded-xl bg-slate-600/95 px-3.5 py-2.5 text-left text-[11px] font-semibold leading-relaxed text-white shadow-lg opacity-0 transition group-hover:opacity-100 group-focus-within:opacity-100 ${placementClass} ${alignClass}`}
      >
        {text}
      </span>
    </span>
  );
}
