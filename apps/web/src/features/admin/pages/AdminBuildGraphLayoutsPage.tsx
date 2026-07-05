import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { RotateCcw, Save } from 'lucide-react';
import { AdminShell, StateMessage } from '../../../components/ui';
import {
  getDefaultBuildGraphLayout,
  resetDefaultBuildGraphLayout,
  saveDefaultBuildGraphLayout,
  type BuildGraphLayoutPosition
} from '../adminApi';

type LayoutStatus = 'PASS' | 'WARN' | 'FAIL';
type LayoutNodeData = {
  category: string;
  categoryLabel: string;
  label: string;
  status: LayoutStatus;
};

const DEFAULT_LAYOUT_POSITIONS: Record<string, BuildGraphLayoutPosition> = {
  CPU: { x: 20, y: 170 },
  MOTHERBOARD: { x: 300, y: 36 },
  RAM: { x: 640, y: 56 },
  GPU: { x: 300, y: 270 },
  PSU: { x: 640, y: 250 },
  CASE: { x: 640, y: 440 },
  COOLER: { x: 300, y: 500 },
  STORAGE: { x: 20, y: 650 },
  PRICE: { x: 300, y: 660 }
};

const TEMPLATE_NODES: LayoutNodeData[] = [
  { category: 'CPU', categoryLabel: 'CPU', label: 'AMD 라이젠 7', status: 'PASS' },
  { category: 'MOTHERBOARD', categoryLabel: '메인보드', label: 'ASUS B650 보드', status: 'WARN' },
  { category: 'RAM', categoryLabel: 'RAM', label: 'DDR5 32GB', status: 'PASS' },
  { category: 'GPU', categoryLabel: 'GPU', label: 'RTX 5070 Ti', status: 'PASS' },
  { category: 'PSU', categoryLabel: '파워', label: '정격 1000W', status: 'PASS' },
  { category: 'CASE', categoryLabel: '케이스', label: 'Airflow Case', status: 'PASS' },
  { category: 'COOLER', categoryLabel: '쿨러', label: '360mm AIO', status: 'PASS' },
  { category: 'STORAGE', categoryLabel: 'SSD', label: 'Samsung 990 PRO', status: 'PASS' },
  { category: 'PRICE', categoryLabel: '총액', label: '4,090,300원', status: 'PASS' }
];

const TEMPLATE_EDGES: Edge[] = [
  edge('edge-cpu-board', 'CPU', 'MOTHERBOARD', '소켓 일치', 'FAIL'),
  edge('edge-board-ram', 'MOTHERBOARD', 'RAM', 'DDR 규격', 'PASS'),
  edge('edge-cpu-gpu', 'CPU', 'GPU', '작업 성능', 'PASS'),
  edge('edge-gpu-psu', 'GPU', 'PSU', '전력 여유', 'PASS'),
  edge('edge-gpu-case', 'GPU', 'CASE', '장착 여유', 'PASS'),
  edge('edge-cooler-case', 'COOLER', 'CASE', '높이 간섭', 'WARN')
];

const nodeTypes = { adminLayoutCard: AdminLayoutCardNode };

export function AdminBuildGraphLayoutsPage() {
  const [positions, setPositions] = useState(DEFAULT_LAYOUT_POSITIONS);
  const [saveState, setSaveState] = useState<'idle' | 'dirty' | 'saved' | 'reset'>('idle');
  const layoutQuery = useQuery({
    queryKey: ['admin-build-graph-layout-default'],
    queryFn: getDefaultBuildGraphLayout
  });
  const saveMutation = useMutation({
    mutationFn: () => saveDefaultBuildGraphLayout(positions),
    onSuccess: (layout) => {
      setPositions(mergePositions(layout.positions));
      setSaveState('saved');
    }
  });
  const resetMutation = useMutation({
    mutationFn: resetDefaultBuildGraphLayout,
    onSuccess: (layout) => {
      setPositions(mergePositions(layout.positions));
      setSaveState('reset');
    }
  });

  useEffect(() => {
    if (layoutQuery.data) {
      setPositions(mergePositions(layoutQuery.data.positions));
      setSaveState('idle');
    }
  }, [layoutQuery.data]);

  const nodes = useMemo(() => templateNodes(positions), [positions]);

  const handleNodeDragStop = (_: unknown, node: Node<LayoutNodeData>) => {
    const category = node.data.category;
    setPositions((current) => ({
      ...current,
      [category]: {
        x: Math.max(0, Math.round(node.position.x)),
        y: Math.max(0, Math.round(node.position.y))
      }
    }));
    setSaveState('dirty');
  };

  return (
    <AdminShell title="관계도 배치">
      <div className="space-y-5">
        <header className="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-5 lg:flex-row lg:items-start lg:justify-between">
          <div>
            <p className="text-xs font-bold text-brand-blue">관계도 레이아웃</p>
            <h1 className="mt-1 text-xl font-black text-brand-navy">관계도 배치 고정</h1>
            <p className="mt-2 max-w-3xl break-keep text-sm leading-6 text-slate-600">
              운영자가 표준 관계도 노드를 드래그해 배치를 저장하면 사용자 화면과 홈 추천 관계도에 같은 category 기준 배치가 적용됩니다.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <span className={`rounded-full px-3 py-1 text-xs font-black ${statusClass(saveState)}`}>
              {saveStateLabel(saveState)}
            </span>
            <button
              type="button"
              onClick={() => resetMutation.mutate()}
              disabled={resetMutation.isPending || saveMutation.isPending}
              className="inline-flex items-center gap-2 rounded border border-slate-200 bg-white px-3 py-2 text-sm font-bold text-brand-navy hover:bg-slate-50 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-400"
            >
              <RotateCcw size={15} />
              기본값으로 초기화
            </button>
            <button
              type="button"
              onClick={() => saveMutation.mutate()}
              disabled={saveMutation.isPending || resetMutation.isPending}
              className="inline-flex items-center gap-2 rounded bg-brand-blue px-4 py-2 text-sm font-bold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300 disabled:text-slate-500"
            >
              <Save size={15} />
              {saveMutation.isPending ? '고정 중' : '고정하기'}
            </button>
          </div>
        </header>

        {layoutQuery.isError ? (
          <StateMessage type="warn" title="배치 조회 실패" body="저장된 관계도 배치를 불러오지 못했습니다. 기본 배치로 편집을 계속할 수 있습니다." />
        ) : null}
        {saveMutation.isError ? (
          <StateMessage type="warn" title="배치 저장 실패" body="관리자 권한 또는 좌표 저장 API 응답을 확인해야 합니다." />
        ) : null}
        {resetMutation.isError ? (
          <StateMessage type="warn" title="초기화 실패" body="기본 배치 초기화 요청이 실패했습니다." />
        ) : null}

        <section className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
          <div className="flex items-center justify-between border-b border-slate-200 px-5 py-3">
            <div>
              <h2 className="text-sm font-black text-brand-navy">표준 관계도 템플릿</h2>
              <p className="mt-1 text-xs text-slate-500">노드를 끌어서 위치를 바꾼 뒤 고정하기를 누르세요.</p>
            </div>
            <div className="text-xs font-bold text-slate-500">{layoutQuery.data?.source === 'SAVED' ? '저장 배치 사용 중' : '기본 배치 사용 중'}</div>
          </div>
          <div data-testid="admin-build-graph-layout-editor" className="h-[720px] bg-[linear-gradient(180deg,#f8fafc_0%,#ffffff_100%)]">
            <ReactFlow
              nodes={nodes}
              edges={TEMPLATE_EDGES}
              nodeTypes={nodeTypes}
              fitView
              fitViewOptions={{ padding: 0.1 }}
              minZoom={0.45}
              maxZoom={1.4}
              panOnDrag
              zoomOnScroll
              zoomOnPinch
              nodesDraggable
              nodesConnectable={false}
              onNodeDragStop={handleNodeDragStop}
              proOptions={{ hideAttribution: true }}
            >
              <Background color="#dbe4f0" gap={18} />
              <Controls showInteractive={false} />
            </ReactFlow>
          </div>
        </section>

        <section className="rounded-lg border border-slate-200 bg-white p-5">
          <h2 className="text-sm font-black text-brand-navy">저장 좌표</h2>
          <div className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {TEMPLATE_NODES.map((node) => {
              const position = positions[node.category] ?? DEFAULT_LAYOUT_POSITIONS[node.category];
              return (
                <div key={node.category} className="flex items-center justify-between rounded border border-slate-100 bg-slate-50 px-3 py-2 text-xs">
                  <span className="font-bold text-slate-700">{node.categoryLabel}</span>
                  <span className="font-mono text-slate-500">x {position.x} · y {position.y}</span>
                </div>
              );
            })}
          </div>
        </section>
      </div>
    </AdminShell>
  );
}

function AdminLayoutCardNode({ data }: NodeProps<Node<LayoutNodeData>>) {
  return (
    <>
      <Handle type="target" position={Position.Left} className="opacity-0" />
      <div
        data-testid={`admin-layout-node-${data.category}`}
        className={`flex h-full w-full flex-col justify-between rounded-[10px] border bg-white px-4 py-3 text-left shadow-[0_14px_30px_rgba(15,23,42,0.08)] ${nodeTone(data.status)}`}
      >
        <div className="flex items-start justify-between gap-3">
          <span className="text-[11px] font-black text-brand-blue">{data.categoryLabel}</span>
          <span className={`rounded px-2 py-0.5 text-[10px] font-black ${chipTone(data.status)}`}>{statusLabel(data.status)}</span>
        </div>
        <div className="line-clamp-2 text-sm font-black leading-5 text-slate-950" title={data.label}>{data.label}</div>
      </div>
      <Handle type="source" position={Position.Right} className="opacity-0" />
    </>
  );
}

function templateNodes(positions: Record<string, BuildGraphLayoutPosition>): Node<LayoutNodeData>[] {
  return TEMPLATE_NODES.map((node) => ({
    id: node.category,
    type: 'adminLayoutCard',
    position: positions[node.category] ?? DEFAULT_LAYOUT_POSITIONS[node.category],
    data: node,
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    style: {
      width: node.category === 'MOTHERBOARD' || node.category === 'CASE' ? 250 : 220,
      height: node.category === 'PRICE' ? 88 : 108
    }
  }));
}

function mergePositions(positions: Record<string, BuildGraphLayoutPosition> = {}) {
  return {
    ...DEFAULT_LAYOUT_POSITIONS,
    ...Object.fromEntries(
      Object.entries(positions).map(([category, position]) => [
        category.toUpperCase(),
        {
          x: Math.max(0, Math.round(position.x)),
          y: Math.max(0, Math.round(position.y))
        }
      ])
    )
  };
}

function edge(id: string, source: string, target: string, label: string, status: LayoutStatus): Edge {
  return {
    id,
    source,
    target,
    label,
    // 'bezier'는 React Flow 내장 타입이 아니다. 'default'가 bezier 곡선을 렌더한다.
    type: 'default',
    markerEnd: {
      type: MarkerType.ArrowClosed,
      color: edgeColor(status),
      width: 18,
      height: 18
    },
    style: {
      stroke: edgeColor(status),
      strokeWidth: status === 'PASS' ? 1.8 : 2.2,
      strokeLinecap: 'round'
    },
    labelStyle: {
      fill: edgeColor(status),
      fontSize: 12,
      fontWeight: 800
    },
    labelBgStyle: {
      fill: '#ffffff',
      fillOpacity: 0.9
    },
    labelBgPadding: [8, 4],
    labelBgBorderRadius: 8
  };
}

function edgeColor(status: LayoutStatus) {
  if (status === 'FAIL') return '#ef4444';
  if (status === 'WARN') return '#f59e0b';
  return '#2563eb';
}

function nodeTone(status: LayoutStatus) {
  if (status === 'FAIL') return 'border-red-400';
  if (status === 'WARN') return 'border-amber-300';
  return 'border-blue-100';
}

function chipTone(status: LayoutStatus) {
  if (status === 'FAIL') return 'bg-red-50 text-red-700';
  if (status === 'WARN') return 'bg-amber-50 text-amber-700';
  return 'bg-emerald-50 text-emerald-700';
}

function statusLabel(status: LayoutStatus) {
  if (status === 'FAIL') return '장착 불가';
  if (status === 'WARN') return '간섭 주의';
  return '호환됨';
}

function saveStateLabel(state: 'idle' | 'dirty' | 'saved' | 'reset') {
  if (state === 'dirty') return '저장되지 않은 변경';
  if (state === 'saved') return '저장 완료';
  if (state === 'reset') return '기본 배치로 초기화됨';
  return '변경 없음';
}

function statusClass(state: 'idle' | 'dirty' | 'saved' | 'reset') {
  if (state === 'dirty') return 'bg-amber-50 text-amber-700';
  if (state === 'saved' || state === 'reset') return 'bg-emerald-50 text-emerald-700';
  return 'bg-slate-100 text-slate-600';
}
