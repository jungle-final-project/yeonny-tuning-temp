import { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { getToken } from '../../lib/api';

export function RequireUser({ children, preserveRedirect = true }: { children: ReactNode; preserveRedirect?: boolean }) {
  const location = useLocation();
  // 토큰이 없으면 로그인으로 보낸다. 만료 토큰(존재하지만 무효)은 페이지가 렌더된 뒤 각 쿼리/가드가
  // 401을 받아 인라인 안내(예: 챗봇 알림, RequireAdmin 메시지)로 처리한다.
  if (!getToken()) {
    const redirect = preserveRedirect
      ? `?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`
      : '';
    return <Navigate to={`/login${redirect}`} replace />;
  }

  return <>{children}</>;
}
