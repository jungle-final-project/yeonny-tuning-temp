import { ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Activity, Bot, Cpu, Download, Gauge, Home, LayoutGrid, LifeBuoy, Play, RefreshCw, Search, Wrench } from 'lucide-react';

type ExportValue = string | number | boolean | null | undefined;

type AdminShellAction = {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  title?: string;
};

type AdminShellProps = {
  children: ReactNode;
  title: string;
  exportRows?: Record<string, ExportValue>[];
  exportFileName?: string;
  action?: AdminShellAction;
};

export function AdminShell({ children, title, exportRows = [], exportFileName = 'admin-export.csv', action }: AdminShellProps) {
  const canExport = exportRows.length > 0;
  return (
    <div className="screen-shell flex bg-slate-100 font-['Noto_Sans_KR']">
      <AdminSidebar />
      <div className="min-w-[1024px] flex-1">
        <div className="flex h-16 items-center justify-between border-b border-slate-200 bg-white px-7">
          <h1 className="text-lg font-bold text-brand-navy">{title}</h1>
          <div className="flex gap-2">
            <Link
              to="/"
              className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-3 py-2 text-xs font-semibold text-brand-navy hover:bg-slate-50"
            >
              <Home size={14} />
              홈으로
            </Link>
            <button
              type="button"
              disabled={!canExport}
              title={canExport ? '현재 페이지 데이터를 CSV로 내보냅니다.' : '내보낼 테이블 데이터가 없습니다.'}
              onClick={() => exportCsv(exportRows, exportFileName)}
              className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-3 py-2 text-xs font-semibold text-brand-navy hover:bg-slate-50 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-400"
            >
              <Download size={14} />
              내보내기
            </button>
            {action ? (
              <button
                type="button"
                disabled={action.disabled}
                title={action.title}
                onClick={action.onClick}
                className="inline-flex items-center gap-1 rounded bg-brand-blue px-3 py-2 text-xs font-semibold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300 disabled:text-slate-500"
              >
                <Play size={14} />
                {action.label}
              </button>
            ) : null}
          </div>
        </div>
        <main className="p-7">{children}</main>
      </div>
    </div>
  );
}

function AdminSidebar() {
  const { pathname } = useLocation();
  const groups = [
    {
      heading: '운영',
      items: [
        { to: '/admin', label: '대시보드', Icon: Home, match: (path: string) => path === '/admin' },
        { to: '/admin/as-tickets', label: 'AS 티켓', Icon: LifeBuoy, match: (path: string) => path.startsWith('/admin/as-tickets') },
        { to: '/admin/support-chat-sessions', label: '상담방', Icon: LifeBuoy, match: (path: string) => path.startsWith('/admin/support-chat-sessions') },
        { to: '/admin/parts', label: '부품/가격', Icon: Cpu, match: (path: string) => path === '/admin/parts' },
        { to: '/admin/assembly', label: '조립 중개', Icon: Wrench, match: (path: string) => path.startsWith('/admin/assembly') },
        { to: '/admin/price-jobs', label: '가격 작업', Icon: RefreshCw, match: (path: string) => path.startsWith('/admin/price-jobs') }
      ]
    },
    {
      heading: 'AI / 추천',
      items: [
        { to: '/admin/agent-sessions', label: '에이전트 세션', Icon: Bot, match: (path: string) => path.startsWith('/admin/agent-sessions') },
        { to: '/admin/tool-invocations', label: '도구 이력', Icon: Activity, match: (path: string) => path.startsWith('/admin/tool-invocations') },
        { to: '/admin/rag-evidence', label: '검색 근거', Icon: Search, match: (path: string) => path.startsWith('/admin/rag-evidence') },
        { to: '/admin/build-graph-layouts', label: '슬롯 보드 배치', Icon: LayoutGrid, match: (path: string) => path.startsWith('/admin/build-graph-layouts') }
      ]
    },
    {
      heading: '시스템',
      items: [
        { to: '/admin/load-tests', label: '부하 테스트', Icon: Gauge, match: (path: string) => path.startsWith('/admin/load-tests') }
      ]
    }
  ];

  return (
    <aside className="w-60 bg-brand-navy px-4 py-6 text-white">
      <div className="mb-8 text-xl font-bold">BuildGraph<br />관리자</div>
      <nav aria-label="관리자 메뉴" className="space-y-5">
        {groups.map((group) => (
          <div key={group.heading}>
            <div className="mb-2 px-3 text-[11px] font-bold uppercase tracking-wider text-slate-400">{group.heading}</div>
            <div className="space-y-1">
              {group.items.map(({ to, label, Icon, match }) => {
                const isActive = match(pathname);
                return (
                  <Link key={to} to={to} aria-current={isActive ? 'page' : undefined} className={`flex h-10 items-center gap-2 rounded px-3 text-sm font-semibold ${isActive ? 'bg-brand-blue text-white' : 'text-slate-300 hover:bg-white/10'}`}>
                    <Icon size={16} />
                    {label}
                  </Link>
                );
              })}
            </div>
          </div>
        ))}
      </nav>
    </aside>
  );
}

function exportCsv(rows: Record<string, ExportValue>[], fileName: string) {
  if (rows.length === 0) {
    return;
  }
  const columns = Array.from(new Set(rows.flatMap((row) => Object.keys(row))));
  const csv = [
    columns.join(','),
    ...rows.map((row) => columns.map((column) => csvCell(row[column])).join(','))
  ].join('\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}

function csvCell(value: ExportValue) {
  const text = value == null ? '' : String(value);
  return `"${text.replace(/"/g, '""')}"`;
}
