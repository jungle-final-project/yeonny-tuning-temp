import { Component, type ErrorInfo, type ReactNode } from 'react';

type ErrorBoundaryProps = {
  children: ReactNode;
};

type ErrorBoundaryState = {
  hasError: boolean;
};

/**
 * 앱 최상위 렌더 예외 방어막. 페이지 컴포넌트가 예상과 다른 API 응답 형태(예: 배열이어야 할
 * 필드가 null)를 만나 렌더 단계에서 TypeError를 던지면, 이 boundary가 없을 때는 React가 루트
 * 전체를 언마운트해 헤더·네비까지 사라진 흰 화면이 되고 새로고침 외 복구가 불가능하다.
 * 이 boundary는 그 예외를 잡아 복구 가능한 fallback을 보여준다.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // 개발/운영 콘솔에서 원인 추적이 가능하도록 남긴다.
    console.error('[ErrorBoundary] 렌더 예외를 잡았습니다.', error, info.componentStack);
  }

  private handleReset = () => {
    this.setState({ hasError: false });
  };

  render() {
    if (!this.state.hasError) {
      return this.props.children;
    }

    return (
      <div className="flex min-h-dvh flex-col items-center justify-center gap-4 bg-slate-50 px-6 text-center">
        <div className="grid h-14 w-14 place-items-center rounded-2xl bg-red-50 text-2xl">⚠️</div>
        <div>
          <h1 className="text-lg font-black text-slate-900">화면을 표시하는 중 문제가 발생했습니다</h1>
          <p className="mt-1 text-sm font-medium text-slate-500">일시적인 오류일 수 있습니다. 아래 버튼으로 다시 시도해 주세요.</p>
        </div>
        <div className="flex gap-2">
          <a
            href="/"
            onClick={this.handleReset}
            className="rounded-md bg-brand-blue px-4 py-2 text-sm font-black text-white transition hover:bg-blue-700"
          >
            홈으로
          </a>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-black text-slate-700 transition hover:border-slate-400"
          >
            새로고침
          </button>
        </div>
      </div>
    );
  }
}
