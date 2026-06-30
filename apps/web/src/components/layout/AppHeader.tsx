import { ReactNode, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Bot, FileText, LifeBuoy, LogIn, LogOut, Search, ShieldCheck, UserRound } from 'lucide-react';
import { getCurrentUser, type CurrentUser } from '../../features/auth/authApi';
import { AUTH_CHANGED_EVENT, ApiError, clearToken, getToken } from '../../lib/api';
import { PrimaryNav } from './PrimaryNav';

export function AppHeader() {
  const navigate = useNavigate();
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [checkingUser, setCheckingUser] = useState(false);

  useEffect(() => {
    let cancelled = false;

    async function loadCurrentUser() {
      if (!getToken()) {
        setUser(null);
        setCheckingUser(false);
        return;
      }

      setCheckingUser(true);
      try {
        const currentUser = await getCurrentUser();
        if (!cancelled) {
          setUser(currentUser);
        }
      } catch (error) {
        if (!cancelled) {
          setUser(null);
        }
        if (error instanceof ApiError && error.status === 401) {
          clearToken();
        }
      } finally {
        if (!cancelled) {
          setCheckingUser(false);
        }
      }
    }

    void loadCurrentUser();
    window.addEventListener(AUTH_CHANGED_EVENT, loadCurrentUser);
    window.addEventListener('storage', loadCurrentUser);

    return () => {
      cancelled = true;
      window.removeEventListener(AUTH_CHANGED_EVENT, loadCurrentUser);
      window.removeEventListener('storage', loadCurrentUser);
    };
  }, []);

  function logout() {
    clearToken();
    navigate('/login');
  }

  return (
    <>
      <div className="border-b border-neutral-900 bg-neutral-950 text-xs text-white">
        <div className="mx-auto flex min-h-[32px] w-full max-w-[1320px] flex-col gap-1 px-4 py-2 sm:flex-row sm:items-center sm:justify-between sm:px-6 lg:px-8 xl:px-0">
          <span className="font-semibold">오늘의 PC 견적 특가 · 내부 자산 기준 가격/호환성 검증</span>
          <span className="text-white/75">{user ? `로그인됨 · ${user.email} · ${user.role}` : checkingUser ? '로그인 상태 확인 중' : '로그인 필요 · 회원가입 · 관리자 · PC Agent'}</span>
        </div>
      </div>
      <header className="border-b border-commerce-line bg-white">
        <div className="mx-auto flex min-h-[82px] w-full max-w-[1320px] flex-wrap items-center gap-3 px-4 py-3 sm:px-6 lg:px-8 xl:px-0">
          <Link to="/" className="flex min-w-0 items-center gap-3 rounded-md focus:outline-none focus:ring-4 focus:ring-blue-100">
            <div className="grid h-11 w-11 shrink-0 place-items-center rounded-md bg-commerce-ink text-sm font-black text-white">BG</div>
            <div>
              <div className="text-xl font-black leading-5 tracking-tight text-commerce-ink">BuildGraph AI</div>
              <div className="text-xs font-semibold text-slate-500">PC build shopping assistant</div>
            </div>
          </Link>
          <div className="order-last flex h-12 w-full items-center rounded-md border border-commerce-ink bg-white px-3 shadow-sm lg:order-none lg:ml-6 lg:w-[min(42vw,620px)]">
            <Search size={18} className="text-slate-500" />
            <input className="ml-2 min-w-0 flex-1 bg-transparent text-sm font-medium outline-none placeholder:text-slate-400" placeholder="예: QHD 게임용 200만원 PC" />
            <button className="rounded bg-commerce-ink px-4 py-2 text-xs font-black text-white hover:bg-slate-700">검색</button>
          </div>
          <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
            <HeaderButton to="/requirements/new" icon={<Bot size={15} />} label="AI 견적" />
            <HeaderButton to="/my/quotes" icon={<FileText size={15} />} label="내 견적함" />
            <HeaderButton to="/support/new" icon={<LifeBuoy size={15} />} label="AS 접수" />
            {user ? (
              <>
                <div className="flex h-9 max-w-[170px] items-center gap-2 rounded-md border border-emerald-200 bg-emerald-50 px-3 text-xs font-bold text-emerald-800 sm:max-w-none">
                  {user.role === 'ADMIN' ? <ShieldCheck size={15} /> : <UserRound size={15} />}
                  <span className="truncate">{user.name || user.email}</span>
                </div>
                <button onClick={logout} className="flex h-9 items-center gap-1 rounded-md bg-commerce-ink px-3 text-xs font-bold text-white hover:bg-slate-700">
                  <LogOut size={15} />
                  로그아웃
                </button>
              </>
            ) : (
              <HeaderButton to="/login" icon={<LogIn size={15} />} label="로그인" dark />
            )}
          </div>
        </div>
      </header>
      <PrimaryNav />
    </>
  );
}

function HeaderButton({ to, icon, label, dark }: { to: string; icon: ReactNode; label: string; dark?: boolean }) {
  return (
    <Link to={to} className={`flex h-9 items-center gap-1 whitespace-nowrap rounded-md px-3 text-xs font-bold transition focus:outline-none focus:ring-4 focus:ring-blue-100 ${dark ? 'bg-commerce-ink text-white hover:bg-slate-700' : 'border border-commerce-line bg-white text-slate-700 hover:border-commerce-ink hover:text-commerce-ink'}`}>
      {icon}
      {label}
    </Link>
  );
}
