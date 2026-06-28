import { ReactNode, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { Screen, StateMessage } from '../../components/ui';
import { ApiError, getToken } from '../../lib/api';
import { getCurrentUser } from './authApi';

type AdminCheckState = 'checking' | 'missing-token' | 'unauthorized' | 'forbidden' | 'allowed';

export function RequireAdmin({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AdminCheckState>('checking');

  useEffect(() => {
    const token = getToken();
    if (!token) {
      setState('missing-token');
      return;
    }

    let cancelled = false;

    getCurrentUser()
      .then((user) => {
        if (!cancelled) {
          setState(user.role === 'ADMIN' ? 'allowed' : 'forbidden');
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setState(error instanceof ApiError && error.status === 401 ? 'unauthorized' : 'forbidden');
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  if (state === 'checking') {
    return <AdminPermissionCheckingPage />;
  }

  if (state === 'allowed') {
    return <>{children}</>;
  }

  return <AdminPermissionRequiredPage reason={state} />;
}

function AdminPermissionCheckingPage() {
  return (
    <Screen>
      <div className="mx-auto mt-24 w-[520px] panel p-8">
        <div className="text-xs font-bold uppercase text-slate-500">Admin access</div>
        <h1 className="mt-2 text-xl font-bold text-brand-navy">관리자 권한 확인 중</h1>
        <p className="mt-3 text-sm leading-6 text-slate-600">
          저장된 로그인 정보로 현재 사용자 권한을 확인하고 있습니다.
        </p>
      </div>
    </Screen>
  );
}

function AdminPermissionRequiredPage({ reason }: { reason: Exclude<AdminCheckState, 'checking' | 'allowed'> }) {
  const message = reason === 'forbidden'
    ? '현재 로그인한 계정에는 관리자 권한이 없습니다.'
    : '관리자 화면을 보려면 먼저 로그인해야 합니다.';

  return (
    <Screen>
      <div className="mx-auto mt-20 grid w-[760px] grid-cols-[88px_1fr] gap-6 panel p-8">
        <div className="grid h-20 w-20 place-items-center rounded bg-brand-pale text-brand-blue">
          <ShieldAlert size={34} />
        </div>
        <div>
          <div className="text-xs font-bold uppercase text-slate-500">Admin access</div>
          <h1 className="mt-2 text-2xl font-bold text-brand-navy">관리자 권한이 필요합니다</h1>
          <p className="mt-3 text-sm leading-6 text-slate-600">
            {message}
          </p>
          <div className="mt-5">
            <StateMessage type="info" title="현재 세션 확인" body="브라우저에 관리자 권한이 확인되지 않아 관리자 화면을 표시하지 않았습니다." />
          </div>
          <div className="mt-6 flex gap-3">
            <Link to="/login" className="rounded bg-brand-blue px-5 py-3 text-sm font-bold text-white">로그인으로 이동</Link>
            <Link to="/" className="rounded border border-slate-300 px-5 py-3 text-sm font-bold">홈으로 이동</Link>
          </div>
        </div>
      </div>
    </Screen>
  );
}
