import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { getToken } from '../../lib/api';
import {
  AI_BUILD_ASSISTANT_VISIBILITY_CHANGED_EVENT,
  isAiAssistantOpen,
  openAiAssistant,
  type AiAssistantVisibilityDetail
} from '../../lib/events';

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
  const location = useLocation();
  const [isAiActive, setIsAiActive] = useState(() => isAiAssistantOpen());
  const baseItemClass = 'relative inline-flex min-h-[46px] shrink-0 items-center justify-center whitespace-nowrap rounded-lg px-3 text-sm font-medium transition-colors after:absolute after:inset-x-3 after:bottom-0 after:h-0.5 after:rounded-full after:transition-colors focus:outline-none focus:ring-4 focus:ring-blue-100 sm:px-4';
  const activeItemClass = 'bg-white text-[#222222] shadow-sm after:bg-[#de6c2d]';
  const inactiveItemClass = 'text-[#595959] after:bg-transparent hover:bg-slate-50 hover:text-[#222222]';

  useEffect(() => {
    const syncAiVisibility = (event: Event) => {
      const detail = (event as CustomEvent<AiAssistantVisibilityDetail>).detail;
      setIsAiActive(detail?.open ?? isAiAssistantOpen());
    };
    setIsAiActive(isAiAssistantOpen());
    window.addEventListener(AI_BUILD_ASSISTANT_VISIBILITY_CHANGED_EVENT, syncAiVisibility);
    return () => window.removeEventListener(AI_BUILD_ASSISTANT_VISIBILITY_CHANGED_EVENT, syncAiVisibility);
  }, []);

  const itemClass = (active: boolean) => `${baseItemClass} ${active ? activeItemClass : inactiveItemClass}`;
  const currentCategory = new URLSearchParams(location.search).get('category');
  const isCurrentItem = (to: string) => {
    if (to === '/self-quote') {
      return location.pathname === '/self-quote';
    }
    if (location.pathname !== '/parts') {
      return false;
    }
    const targetCategory = new URLSearchParams(to.split('?')[1] ?? '').get('category');
    return targetCategory ? currentCategory === targetCategory : currentCategory === null;
  };

  return (
    <nav aria-label="견적 및 PC 부품 카테고리" className="border-b border-commerce-line bg-[#f7f7f8]">
      <div className="mx-auto flex min-h-[50px] w-full max-w-[920px] items-center overflow-x-auto px-2 sm:px-6 lg:px-8 xl:px-0">
        <div className="flex min-w-max flex-1 items-center justify-between gap-1">
          <button type="button" onClick={() => {
            if (!getToken()) {
              navigate(`/login?redirect=${encodeURIComponent('/?assistant=open')}`);
              return;
            }
            openAiAssistant({ placement: 'side' });
          }} aria-pressed={isAiActive} className={`${itemClass(isAiActive)} gap-1.5`}>
            <Sparkles size={16} aria-hidden="true" />
            AI 견적
          </button>
          {PART_NAV_ITEMS.map(([to, label]) => {
            const active = isCurrentItem(to);
            return (
              <Link key={to} to={to} aria-current={active ? 'page' : undefined} className={itemClass(active)}>
                {label}
              </Link>
            );
          })}
        </div>
      </div>
    </nav>
  );
}
