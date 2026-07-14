import { Link, useNavigate } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { getToken } from '../../lib/api';
import { openAiAssistant } from '../../lib/events';

const PART_NAV_ITEMS: Array<[string, string]> = [
  ['/self-quote', '셀프 견적'],
  ['/parts?category=CPU', 'CPU'],
  ['/parts?category=GPU', 'GPU'],
  ['/parts?category=MOTHERBOARD', '메인보드'],
  ['/parts?category=RAM', '메모리'],
  ['/parts?category=STORAGE', '저장장치'],
  ['/parts?category=PSU', '파워'],
  ['/parts?category=CASE', '케이스'],
  ['/parts', '전체 부품']
];

export function PrimaryNav() {
  const navigate = useNavigate();
  const itemClass = 'inline-flex min-h-[46px] shrink-0 items-center justify-center whitespace-nowrap rounded-lg px-3 text-sm font-medium text-[#595959] transition hover:bg-slate-50 hover:text-[#222222] focus:outline-none focus:ring-4 focus:ring-blue-100 sm:px-4';

  return (
    <nav aria-label="견적 및 PC 부품 카테고리" className="border-b border-commerce-line bg-white">
      <div className="mx-auto flex min-h-[50px] w-full max-w-[920px] items-center overflow-x-auto px-2 sm:px-6 lg:px-8 xl:px-0">
        <div className="flex min-w-max flex-1 items-center justify-between gap-1">
          <button type="button" onClick={() => {
            if (!getToken()) {
              navigate(`/login?redirect=${encodeURIComponent('/?assistant=open')}`);
              return;
            }
            openAiAssistant({ placement: 'side' });
          }} className={`${itemClass} gap-1.5`}>
            <Sparkles size={16} aria-hidden="true" />
            AI 견적
          </button>
          {PART_NAV_ITEMS.map(([to, label]) => (
            <Link key={to} to={to} className={itemClass}>{label}</Link>
          ))}
        </div>
      </div>
    </nav>
  );
}