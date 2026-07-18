import '@fontsource/outfit/500.css';
import { FormEvent, ReactNode, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ChevronDown, LifeBuoy, LogIn, LogOut, Search, ShieldCheck, ShoppingCart, UserRound, Wrench } from 'lucide-react';
import { getCurrentUser, logout as logoutApi, type CurrentUser } from '../../features/auth/authApi';
import { AUTH_CHANGED_EVENT, ApiError, clearToken, getCachedAuthUser, getRefreshToken, getToken } from '../../lib/api';
import { openAiAssistant } from '../../lib/events';
import { PrimaryNav } from './PrimaryNav';

export function AppHeader() {
  const navigate = useNavigate();
  const location = useLocation();
  const [user, setUser] = useState<CurrentUser | null>(() => readCachedCurrentUser());
  const [searchInput, setSearchInput] = useState('');
  const [headerSearchMode, setHeaderSearchMode] = useState<'general' | 'ai'>('ai');
  const [isSelfQuoteHeaderOpen, setIsSelfQuoteHeaderOpen] = useState(false);
  const isSelfQuoteRoute = location.pathname === '/self-quote';
  useEffect(() => {
    setIsSelfQuoteHeaderOpen(false);
  }, [location.pathname]);


  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const prompt = searchInput.trim();
    if (!prompt) return;
    const generalSearchTarget = `/parts?q=${encodeURIComponent(prompt)}`;
    if (!getToken()) {
      const redirectTarget = headerSearchMode === 'general' ? generalSearchTarget : '/?assistant=open';
      navigate(`/login?redirect=${encodeURIComponent(redirectTarget)}`);
      return;
    }
    if (headerSearchMode === 'general') {
      navigate(generalSearchTarget);
      setSearchInput('');
      return;
    }
    openAiAssistant({ prefill: prompt, autoSubmit: true });
    setSearchInput('');
  }

  useEffect(() => {
    let cancelled = false;

    async function loadCurrentUser() {
      if (!getToken()) {
        setUser(null);
        return;
      }
      const cachedUser = readCachedCurrentUser();
      if (cachedUser) {
        setUser(cachedUser);
        return;
      }
      try {
        const currentUser = await getCurrentUser();
        if (!cancelled) setUser(currentUser);
      } catch (error) {
        if (!cancelled) setUser(null);
        if (error instanceof ApiError && error.status === 401) clearToken();
      }
    }

    function handleAuthChanged(event: Event) {
      const userFromEvent = event instanceof CustomEvent ? event.detail?.user : null;
      if (isCurrentUser(userFromEvent)) {
        setUser(userFromEvent);
        return;
      }
      void loadCurrentUser();
    }

    void loadCurrentUser();
    window.addEventListener(AUTH_CHANGED_EVENT, handleAuthChanged);
    window.addEventListener('storage', loadCurrentUser);
    return () => {
      cancelled = true;
      window.removeEventListener(AUTH_CHANGED_EVENT, handleAuthChanged);
      window.removeEventListener('storage', loadCurrentUser);
    };
  }, []);

  async function logout() {
    const refreshToken = getRefreshToken();
    try {
      if (refreshToken) await logoutApi(refreshToken);
    } finally {
      clearToken();
      navigate('/login');
    }
  }

  const isGeneralSearch = headerSearchMode === 'general';
  const searchInputLabel = isGeneralSearch ? '부품 일반 검색' : 'AI에게 견적 질문하기';
  const searchSubmitLabel = isGeneralSearch ? '부품 검색' : 'AI 견적 검색';
  const searchPlaceholder = isGeneralSearch
    ? '부품명이나 모델명을 검색해보세요. 예: RTX 5060 Ti'
    : '어떤 PC를 맞춰드릴까요? 예: QHD 게임용 200만원 PC';
  const accountDisplayName = user?.role === 'ADMIN' && user.name === 'BuildGraph Admin'
    ? 'admin'
    : user?.name || '다짜줘 사용자';

  return (
    <div className={isSelfQuoteRoute ? 'relative z-50 h-0' : ''}>
      {isSelfQuoteRoute && !isSelfQuoteHeaderOpen ? (
        <button
          type="button"
          aria-haspopup="menu"
          aria-expanded={false}
          onClick={() => setIsSelfQuoteHeaderOpen(true)}
          className="absolute right-4 top-0 z-[60] rounded-b-lg border border-t-0 border-commerce-line bg-[#f7f7f8] px-4 py-2 text-sm font-bold text-[#595959] shadow-md transition hover:text-[#222222] focus:outline-none focus:ring-4 focus:ring-blue-100 sm:right-6 lg:right-8"
        >
          &#xC0C1;&#xB2E8; &#xBA54;&#xB274;
        </button>
      ) : null}
      <div className={isSelfQuoteRoute
        ? 'absolute inset-x-0 top-0 z-50 shadow-xl transition duration-150 ' + (isSelfQuoteHeaderOpen ? 'visible translate-y-0 opacity-100' : 'pointer-events-none invisible -translate-y-2 opacity-0')
        : ''}>
      <header className="bg-[#f7f7f8]">
        <div className="mx-auto grid min-h-[68px] w-full max-w-[1320px] grid-cols-[auto_minmax(0,1fr)] items-center gap-x-3 gap-y-3 px-4 pb-[7px] pt-3 sm:px-6 lg:grid-cols-[minmax(180px,1fr)_minmax(360px,760px)_minmax(180px,1fr)] lg:gap-x-6 lg:px-8 xl:px-0">
          <div className="flex min-w-0 items-center gap-5">
            <Link to="/" aria-label="다짜줘 홈" className="flex h-10 min-w-0 items-center rounded-md focus:outline-none focus:ring-4 focus:ring-blue-100">
              <span className="relative -top-[2px] text-[28px] leading-none tracking-[-0.025em] text-[#de6c2d] sm:text-[33.6px]" style={{ fontFamily: 'Outfit, sans-serif', fontWeight: 500 }}>Dazzajo</span>
            </Link>
            <div role="group" aria-label="검색 방식" className="hidden shrink-0 items-center gap-3 lg:flex">
              <button
                type="button"
                aria-pressed={headerSearchMode === 'general'}
                onClick={() => setHeaderSearchMode('general')}
                className={'flex h-10 items-center border-b-2 text-[15px] font-medium leading-none transition ' + (headerSearchMode === 'general' ? 'border-[#de6c2d] text-[#222222]' : 'border-transparent text-[#595959] hover:text-[#222222]')}
              >
                일반검색
              </button>
              <button
                type="button"
                aria-pressed={headerSearchMode === 'ai'}
                onClick={() => setHeaderSearchMode('ai')}
                className={'flex h-10 items-center border-b-2 text-[15px] font-medium leading-none transition ' + (headerSearchMode === 'ai' ? 'border-[#de6c2d] text-[#222222]' : 'border-transparent text-[#595959] hover:text-[#222222]')}
              >
                AI 검색
              </button>
            </div>
          </div>

          <form data-testid="header-ai-search" onSubmit={submitSearch} className="order-last col-span-2 flex h-12 w-full min-w-0 items-center rounded-full border-2 border-commerce-ink bg-[#f7f7f8] pl-5 pr-1.5 shadow-sm transition focus-within:ring-4 focus-within:ring-blue-100 lg:order-none lg:col-span-1">
            <input
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              aria-label={searchInputLabel}
              className="min-w-0 flex-1 bg-transparent pr-3 text-sm font-semibold outline-none placeholder:font-medium placeholder:text-slate-400"
              placeholder={searchPlaceholder}
            />
            <button type="submit" aria-label={searchSubmitLabel} className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-[#de6c2d] text-white transition hover:bg-[#c45c22] focus:outline-none focus:ring-4 focus:ring-blue-100">
              <Search size={18} aria-hidden="true" />
            </button>
          </form>

          <div className="col-start-2 row-start-1 flex items-center justify-end gap-1 lg:col-start-3">
            <HeaderIconLink to="/my/quotes" icon={<ShoppingCart size={21} />} label="내 견적함" />
            {user ? (
              <details data-testid="header-account-slot" className="group relative w-[118px] shrink-0">
                <summary aria-label={'계정 메뉴: ' + accountDisplayName} className="flex h-10 w-full cursor-pointer list-none items-center gap-2 rounded-lg px-2 text-[#595959] transition hover:bg-slate-100 hover:text-[#222222] focus:outline-none focus:ring-4 focus:ring-blue-100 [&::-webkit-details-marker]:hidden">
                  <UserRound size={21} className="shrink-0" aria-hidden="true" />
                  <span data-testid="header-account-name" className="w-[72px] truncate text-left text-[15px] font-medium leading-none">{accountDisplayName}</span>
                  <ChevronDown size={10} className="shrink-0 transition group-open:rotate-180" aria-hidden="true" />
                </summary>
                <div className="absolute right-0 top-[calc(100%+8px)] z-50 w-56 overflow-hidden rounded-xl border border-commerce-line bg-white py-2 shadow-xl">
                  <div className="border-b border-commerce-line px-4 pb-3 pt-2">
                    <div className="truncate text-sm font-black text-commerce-ink">{accountDisplayName}</div>
                    <div className="mt-0.5 truncate text-xs text-slate-500">{user.email}</div>
                  </div>
                  <AccountMenuLink to="/my/profile" icon={<UserRound size={16} />} label="마이페이지" />
                  {user.role === 'ADMIN' ? <AccountMenuLink to="/admin" icon={<ShieldCheck size={16} />} label="관리자" /> : null}
                  <AccountMenuLink to="/support/new" icon={<LifeBuoy size={16} />} label="AS 접수" />
                  {user.role === 'USER' ? (
                    <AccountMenuLink
                      to="/technician"
                      icon={<Wrench size={16} />}
                      label="기사 포털"
                    />
                  ) : null}
                  <button type="button" onClick={logout} className="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm font-bold text-slate-600 transition hover:bg-slate-100 hover:text-commerce-ink focus:outline-none focus:bg-slate-100">
                    <LogOut size={16} aria-hidden="true" />
                    로그아웃
                  </button>
                </div>
              </details>
            ) : (
              <Link
                to="/login"
                aria-label="계정 로그인"
                data-testid="header-account-slot"
                className="flex h-10 w-[118px] shrink-0 items-center gap-2 rounded-lg px-2 text-[#595959] transition hover:bg-slate-100 hover:text-[#222222] focus:outline-none focus:ring-4 focus:ring-blue-100"
              >
                <LogIn size={21} className="shrink-0" aria-hidden="true" />
                <span data-testid="header-account-name" aria-hidden="true" className="block w-[72px] shrink-0" />
                <span aria-hidden="true" className="block h-2.5 w-2.5 shrink-0" />
              </Link>
            )}
          </div>
        </div>
      </header>
      <PrimaryNav />
      {isSelfQuoteRoute ? (
        <button
          type="button"
          onClick={() => setIsSelfQuoteHeaderOpen(false)}
          className="absolute right-4 top-full rounded-b-lg border border-t-0 border-commerce-line bg-[#f7f7f8] px-4 py-2 text-sm font-bold text-[#595959] shadow-md transition hover:text-[#222222] focus:outline-none focus:ring-4 focus:ring-blue-100 sm:right-6 lg:right-8"
        >
          &#xB2EB;&#xAE30;
        </button>
      ) : null}
      </div>
    </div>
  );
}

function isCurrentUser(value: unknown): value is CurrentUser {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.id === 'string' &&
    typeof candidate.email === 'string' &&
    typeof candidate.name === 'string' &&
    (candidate.role === 'USER' || candidate.role === 'ADMIN')
  );
}

function readCachedCurrentUser() {
  const cachedUser = getCachedAuthUser();
  return isCurrentUser(cachedUser) && getToken() ? cachedUser : null;
}

function HeaderIconLink({ to, icon, label }: { to: string; icon: ReactNode; label: string }) {
  return (
    <Link to={to} aria-label={label} title={label} className="grid h-10 w-10 shrink-0 place-items-center rounded-lg text-[#595959] transition hover:bg-slate-100 hover:text-[#222222] focus:outline-none focus:ring-4 focus:ring-blue-100">
      <span aria-hidden="true">{icon}</span>
    </Link>
  );
}

function AccountMenuLink({ to, icon, label }: { to: string; icon: ReactNode; label: string }) {
  return (
    <Link to={to} className="flex items-center gap-3 px-4 py-2.5 text-sm font-bold text-slate-600 transition hover:bg-slate-100 hover:text-commerce-ink focus:outline-none focus:bg-slate-100">
      <span aria-hidden="true">{icon}</span>
      <span>{label}</span>
    </Link>
  );
}
