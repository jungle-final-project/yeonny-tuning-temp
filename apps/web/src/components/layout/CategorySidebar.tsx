import { Link } from 'react-router-dom';

type CategorySidebarItem = string | {
  label: string;
  value: string;
};

const categoryRoutes: Record<string, string> = {
  'AI 추천': '/requirements/new',
  '셀프 견적': '/self-quote',
  CPU: '/parts?category=CPU',
  '메인보드': '/parts?category=MOTHERBOARD',
  RAM: '/parts?category=RAM',
  GPU: '/parts?category=GPU',
  SSD: '/parts?category=STORAGE',
  '파워': '/parts?category=PSU',
  '케이스': '/parts?category=CASE',
  '쿨러': '/parts?category=COOLER',
  '목표가 알림': '/my/quotes',
  'PC Agent': '/support/new',
  'PCAgent': '/support/new',
  'AS 접수': '/support/new'
};

export function CategorySidebar({
  items,
  activeValue,
  onSelect
}: {
  items: CategorySidebarItem[];
  activeValue?: string;
  onSelect?: (value: string) => void;
}) {
  return (
    <aside className="panel w-full p-4 xl:w-[216px]">
      <div className="mb-1 text-base font-black text-commerce-ink">PC 카테고리</div>
      <div className="mb-4 text-xs font-medium text-slate-500">부품별 쇼핑 필터</div>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 xl:block xl:space-y-2">
        {items.map((item, idx) => {
          const label = typeof item === 'string' ? item : item.label;
          const value = typeof item === 'string' ? item : item.value;
          const active = activeValue === value;

          if (onSelect) {
            return (
              <button
                key={label}
                type="button"
                onClick={() => onSelect(value)}
                className={`block min-h-11 w-full rounded-md border px-3 py-2 text-left text-sm transition hover:border-commerce-ink hover:bg-white ${active ? 'border-[#de6c2d] bg-[#de6c2d] font-black text-white' : 'border-commerce-line bg-slate-50 font-bold text-slate-800'}`}
              >
                {label}
              </button>
            );
          }

          return (
            <Link key={label} to={categoryRoutes[label] ?? (idx === 0 ? '/requirements/new' : idx === 1 ? '/self-quote' : '/')} className="block min-h-11 rounded-md border border-commerce-line bg-slate-50 px-3 py-2 text-sm font-bold text-slate-800 hover:border-commerce-ink hover:bg-white">
              {label}
            </Link>
          );
        })}
      </div>
    </aside>
  );
}
