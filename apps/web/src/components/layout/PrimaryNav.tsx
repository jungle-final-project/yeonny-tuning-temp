import { NavLink } from 'react-router-dom';

export function PrimaryNav() {
  const nav = [
    ['/', '홈'],
    ['/requirements/new', 'AI 견적'],
    ['/self-quote', '셀프 견적'],
    ['/builds/00000000-0000-4000-8000-000000002001', '추천 결과'],
    ['/my/quotes', '목표가 알림'],
    ['/support/new', 'AS 접수'],
    ['/admin', '관리자']
  ];
  return (
    <nav className="border-b border-commerce-line bg-white text-sm text-commerce-ink">
      <div className="mx-auto flex min-h-[46px] w-full max-w-[1320px] items-center gap-1 overflow-x-auto px-2 sm:px-6 lg:px-8 xl:px-0">
        {nav.map(([to, label]) => (
          <NavLink key={to} to={to} className={({ isActive }) => `whitespace-nowrap border-b-2 px-4 py-3 font-black transition sm:px-6 ${isActive ? 'border-commerce-ink text-commerce-ink' : 'border-transparent text-slate-500 hover:border-slate-300 hover:text-commerce-ink'}`}>
            {label}
          </NavLink>
        ))}
      </div>
    </nav>
  );
}
