import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import { ErrorBoundary } from './components/feedback/ErrorBoundary';
import { AUTH_CHANGED_EVENT } from './lib/api';
import './index.css';

const queryClient = new QueryClient();

// 로그인/로그아웃/계정 전환 시 이전 사용자의 화면 데이터(견적 드래프트·내 견적함·추천 등)가
// 다음 사용자에게 남지 않도록 쿼리 캐시 전체를 비운다. 토큰 자동 갱신은 이 이벤트를 쏘지 않는다.
window.addEventListener(AUTH_CHANGED_EVENT, () => {
  queryClient.clear();
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
    </ErrorBoundary>
  </React.StrictMode>
);
