import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Background,
  Controls,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  type Edge,
  type Node
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { AlertTriangle, CheckCircle2, GitBranch, Info, Maximize2, X } from 'lucide-react';
import {
  PART_CATEGORY_LABELS,
  type BuildGraphNode,
  type BuildGraphEdge,
  type BuildGraphResolveResponse,
  type BuildGraphStatus,
  type PartCategory
} from '../aiSelection';
import { listCompatiblePartCandidates } from '../../parts/partsApi';
import type { CompatiblePartCandidate, PartRow } from '../../parts/types';

type BuildDependencyGraphProps = {
  graph?: BuildGraphResolveResponse | null;
  isLoading?: boolean;
  isError?: boolean;
  title?: string;
  subtitle?: string;
  onCategorySelect?: (category: PartCategory) => void;
  candidateContext?: {
    source: 'AI_BUILD' | 'QUOTE_DRAFT_CURRENT';
    items?: Array<{ partId: string; category: string; quantity: number }>;
    readOnly?: boolean;
    selectedPartIds?: Set<string>;
    onSelectPart?: (part: PartRow) => void;
  };
};
type CandidateContext = NonNullable<BuildDependencyGraphProps['candidateContext']>;

const categoryOrder = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'PSU', 'CASE', 'COOLER', 'STORAGE', 'PRICE'];
const DEFAULT_NODE_DIAMETER = 112;
const categoryPositions: Record<string, { x: number; y: number }> = {
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

export function BuildDependencyGraph({
  graph,
  isLoading,
  isError,
  title = '견적 관계도',
  subtitle = '선택한 부품이 전력, 규격, 호환성에 주는 영향을 시각화합니다.',
  onCategorySelect,
  candidateContext
}: BuildDependencyGraphProps) {
  const [activeEdge, setActiveEdge] = useState<BuildGraphEdge | null>(null);
  const [activeNodeId, setActiveNodeId] = useState<string | null>(null);
  const [isDesktopViewport, setIsDesktopViewport] = useState(false);
  const [showFloatingGraph, setShowFloatingGraph] = useState(false);
  const graphCanvasRef = useRef<HTMLDivElement | null>(null);
  const hasSeenGraphRef = useRef(false);
  const activeNode = graph?.nodes.find((node) => node.id === activeNodeId) ?? null;
  const activeNodeCategory = activeNode && typeof activeNode.category === 'string' && isPartCategory(activeNode.category)
    ? activeNode.category
    : null;
  const candidateItems = candidateContext?.items ?? [];
  const candidateQuery = useQuery({
    queryKey: [
      'parts',
      'compatible-candidates',
      candidateContext?.source,
      activeNodeCategory,
      candidateItems.map((item) => `${item.partId}:${item.category}:${item.quantity}`).sort().join('|')
    ],
    queryFn: () => listCompatiblePartCandidates({
      source: candidateContext?.source ?? 'AI_BUILD',
      category: activeNodeCategory ?? '',
      items: candidateItems,
      limit: 5
    }),
    enabled: Boolean(candidateContext && activeNodeCategory)
  });
  const { nodes, edges } = useMemo(() => toFlowElements(graph), [graph]);
  const canShowFloatingGraph = Boolean(graph && graph.nodes.length > 0 && !isLoading && !isError);

  useEffect(() => {
    const mediaQuery = window.matchMedia('(min-width: 1024px)');
    const syncViewport = () => setIsDesktopViewport(mediaQuery.matches);
    syncViewport();
    mediaQuery.addEventListener('change', syncViewport);
    return () => mediaQuery.removeEventListener('change', syncViewport);
  }, []);

  useEffect(() => {
    const graphElement = graphCanvasRef.current;
    if (!graphElement || !canShowFloatingGraph || !isDesktopViewport) {
      hasSeenGraphRef.current = false;
      setShowFloatingGraph(false);
      return;
    }

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.intersectionRatio >= 0.18) {
          hasSeenGraphRef.current = true;
          setShowFloatingGraph(false);
          return;
        }
        setShowFloatingGraph(hasSeenGraphRef.current);
      },
      { threshold: [0, 0.18, 0.5, 1] }
    );
    observer.observe(graphElement);
    return () => observer.disconnect();
  }, [canShowFloatingGraph, isDesktopViewport]);

  const handleNodeClick = (node: Node) => {
    setActiveNodeId(String(node.id));
    setActiveEdge(null);
    const category = node.data.category;
    if (typeof category === 'string' && isPartCategory(category)) {
      onCategorySelect?.(category);
    }
  };

  const handleEdgeClick = (edge: Edge) => {
    const graphEdge = graph?.edges.find((item) => item.id === edge.id);
    setActiveNodeId(null);
    setActiveEdge(graphEdge ?? null);
  };

  const scrollToMainGraph = () => {
    graphCanvasRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  return (
    <section data-testid="build-dependency-graph" className="panel overflow-hidden">
      <div className="flex flex-col gap-4 border-b border-commerce-line bg-white px-5 py-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <div className="flex items-center gap-2 text-xs font-black text-brand-blue">
            <GitBranch size={15} />
            Dependency graph
          </div>
          <h2 className="mt-1 text-xl font-black text-commerce-ink">{title}</h2>
          <p className="mt-1 max-w-3xl break-keep text-sm leading-6 text-slate-500">{graph?.summary ?? subtitle}</p>
        </div>
        <div className="grid grid-cols-3 gap-2 text-center text-xs sm:min-w-[260px]">
          <GraphStat label="노드" value={graph?.nodes.length ?? 0} />
          <GraphStat label="관계" value={graph?.edges.length ?? 0} />
          <GraphStat label="주의" value={graph?.insights.filter((insight) => insight.status !== 'PASS').length ?? 0} tone="warn" />
        </div>
      </div>

      {isLoading ? (
        <div className="grid min-h-[320px] place-items-center p-6 text-sm font-bold text-slate-500">
          관계 그래프를 계산하는 중입니다.
        </div>
      ) : isError ? (
        <div className="m-5 rounded-lg border border-orange-200 bg-orange-50 p-5 text-sm font-bold text-orange-700">
          관계 그래프 API를 불러오지 못했습니다.
        </div>
      ) : !graph || graph.nodes.length === 0 ? (
        <div className="m-5 rounded-lg border border-dashed border-blue-200 bg-blue-50/70 p-6 text-center">
          <div className="mx-auto grid h-12 w-12 place-items-center rounded-xl bg-white text-brand-blue shadow-product">
            <GitBranch size={23} />
          </div>
          <h3 className="mt-3 text-base font-black text-commerce-ink">부품을 담으면 관계가 그려집니다</h3>
          <p className="mx-auto mt-2 max-w-xl break-keep text-sm leading-6 text-slate-500">
            CPU 소켓, RAM 규격, GPU 전력, 케이스 장착 제약처럼 서로 영향을 주는 조건을 한 화면에서 확인합니다.
          </p>
        </div>
      ) : (
        <div className="grid gap-0 lg:grid-cols-[minmax(0,1fr)_320px]">
          <div
            ref={graphCanvasRef}
            data-testid="graph-flow-canvas"
            className="relative min-w-0 border-b border-commerce-line bg-[linear-gradient(180deg,#f8fafc_0%,#ffffff_100%)] lg:border-b-0 lg:border-r"
          >
            <div className="h-[430px] lg:h-[680px] xl:h-[720px]">
              <ReactFlow
                nodes={nodes}
                edges={edges}
                fitView
                fitViewOptions={{ padding: 0.12 }}
                minZoom={0.45}
                maxZoom={1.35}
                proOptions={{ hideAttribution: true }}
                onNodeClick={(_, node: Node) => handleNodeClick(node)}
                onEdgeClick={(_, edge: Edge) => handleEdgeClick(edge)}
              >
                <Background color="#dbe4f0" gap={18} />
                {isDesktopViewport ? (
                  <MiniMap pannable zoomable nodeColor={(node) => statusColor(String(node.data.status ?? 'PASS'))} />
                ) : null}
                {isDesktopViewport ? <Controls showInteractive={false} /> : null}
              </ReactFlow>
            </div>

            {activeNode ? (
              <GraphNodeCandidatePanel
                activeNode={activeNode}
                activeNodeCategory={activeNodeCategory}
                candidateContext={candidateContext}
                candidates={candidateQuery.data?.items ?? []}
                isLoading={candidateQuery.isLoading}
                isError={candidateQuery.isError}
                rejectedCount={candidateQuery.data?.rejectedCount ?? 0}
              />
            ) : null}
          </div>
          <aside className="min-w-0 bg-white p-5">
            <div className="mb-4 flex items-center justify-between gap-3">
              <h3 className="text-sm font-black text-commerce-ink">영향 요약</h3>
              <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-1 text-[11px] font-black text-slate-600">
                <Maximize2 size={12} />
                Focused
              </span>
            </div>

            {!activeEdge ? (
              <div className="mb-4 rounded-lg border border-dashed border-commerce-line bg-slate-50 p-3">
                <div className="text-sm font-black text-commerce-ink">관계를 선택하세요</div>
                <p className="mt-1 break-keep text-xs leading-5 text-slate-500">
                  선을 누르면 두 부품 사이의 제약과 판단 근거를 확인할 수 있습니다.
                </p>
              </div>
            ) : null}

            {activeEdge ? (
              <div className={`mb-4 rounded-lg border p-3 ${statusPanelTone(activeEdge.status)}`}>
                <div className="mb-1 flex items-center gap-2 text-xs font-black">
                  {statusIcon(activeEdge.status)}
                  선택한 관계
                </div>
                <div className="text-sm font-black text-commerce-ink">{activeEdge.label}</div>
                <p className="mt-1 break-keep text-xs leading-5 text-slate-600">{activeEdge.summary}</p>
              </div>
            ) : null}

            <div className="space-y-2">
              {graph.insights.map((insight) => (
                <article
                  key={insight.id}
                  className={`w-full rounded-lg border p-3 text-left ${statusPanelTone(insight.status)}`}
                >
                  <div className="flex items-center justify-between gap-3">
                    <div className="flex items-center gap-2 text-xs font-black">
                      {statusIcon(insight.status)}
                      {statusLabel(insight.status)}
                    </div>
                    <span className="text-[11px] font-black text-slate-400">{insight.relatedNodeIds.length} nodes</span>
                  </div>
                  <div className="mt-2 text-sm font-black text-commerce-ink">{insight.title}</div>
                  <p className="mt-1 break-keep text-xs leading-5 text-slate-600">{insight.description}</p>
                </article>
              ))}
            </div>

            <div className="mt-4 rounded-lg border border-commerce-line bg-slate-50 p-3">
              <div className="mb-2 flex items-center gap-2 text-xs font-black text-slate-700">
                <Info size={14} />
                그래프 읽는 법
              </div>
              <p className="break-keep text-xs leading-5 text-slate-500">
                노드는 부품과 제약이고, 선은 선택이 영향을 주는 관계입니다. 노란 선은 확인 필요, 빨간 선은 교체 후보를 먼저 봐야 하는 관계입니다.
              </p>
            </div>
          </aside>
        </div>
      )}
      {showFloatingGraph && canShowFloatingGraph ? (
        <FloatingDependencyGraph
          nodes={nodes}
          edges={edges}
          activeNode={activeNode}
          activeNodeCategory={activeNodeCategory}
          candidateContext={candidateContext}
          candidates={candidateQuery.data?.items ?? []}
          isLoading={candidateQuery.isLoading}
          isError={candidateQuery.isError}
          rejectedCount={candidateQuery.data?.rejectedCount ?? 0}
          onNodeClick={handleNodeClick}
          onEdgeClick={handleEdgeClick}
          onScrollToMainGraph={scrollToMainGraph}
        />
      ) : null}
    </section>
  );
}

function FloatingDependencyGraph({
  nodes,
  edges,
  activeNode,
  activeNodeCategory,
  candidateContext,
  candidates,
  isLoading,
  isError,
  rejectedCount,
  onNodeClick,
  onEdgeClick,
  onScrollToMainGraph
}: {
  nodes: Node[];
  edges: Edge[];
  activeNode: BuildGraphNode | null;
  activeNodeCategory: PartCategory | null;
  candidateContext?: CandidateContext;
  candidates: CompatiblePartCandidate[];
  isLoading: boolean;
  isError: boolean;
  rejectedCount: number;
  onNodeClick: (node: Node) => void;
  onEdgeClick: (edge: Edge) => void;
  onScrollToMainGraph: () => void;
}) {
  return (
    <div className="fixed bottom-5 left-5 z-40 hidden max-w-[calc(100vw-2.5rem)] items-end gap-3 lg:flex">
      <div
        data-testid="floating-dependency-graph"
        className="w-[360px] overflow-hidden rounded-xl border border-commerce-line bg-white shadow-2xl"
      >
        <div className="flex items-center justify-between gap-3 border-b border-commerce-line px-3 py-2">
          <div className="min-w-0">
            <div className="text-[11px] font-black text-brand-blue">Dependency graph</div>
            <div className="truncate text-xs font-black text-commerce-ink">미니 관계도</div>
          </div>
          <button
            type="button"
            onClick={onScrollToMainGraph}
            className="shrink-0 rounded-md border border-commerce-line bg-white px-2 py-1 text-[11px] font-black text-slate-600 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-brand-blue"
          >
            원래 그래프로 이동
          </button>
        </div>
        <div className="h-[240px] bg-[linear-gradient(180deg,#f8fafc_0%,#ffffff_100%)]">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            fitView
            fitViewOptions={{ padding: 0.18 }}
            minZoom={0.18}
            maxZoom={1.15}
            nodesDraggable={false}
            nodesConnectable={false}
            proOptions={{ hideAttribution: true }}
            onNodeClick={(_, node: Node) => onNodeClick(node)}
            onEdgeClick={(_, edge: Edge) => onEdgeClick(edge)}
          >
            <Background color="#dbe4f0" gap={14} />
            <Controls showInteractive={false} />
          </ReactFlow>
        </div>
      </div>
      {activeNode ? (
        <div data-testid="floating-graph-candidate-panel" className="max-h-[72vh] w-[360px] overflow-y-auto">
          <GraphNodeCandidatePanel
            variant="floating"
            activeNode={activeNode}
            activeNodeCategory={activeNodeCategory}
            candidateContext={candidateContext}
            candidates={candidates}
            isLoading={isLoading}
            isError={isError}
            rejectedCount={rejectedCount}
          />
        </div>
      ) : null}
    </div>
  );
}

function toFlowElements(graph?: BuildGraphResolveResponse | null): { nodes: Node[]; edges: Edge[] } {
  if (!graph) return { nodes: [], edges: [] };
  const focusNodeIds = new Set(graph.focusNodeIds);
  const nodes = graph.nodes.map((node, index) => {
    const category = String(node.category ?? node.id).toUpperCase();
    const basePosition = categoryPositions[category] ?? {
      x: 20 + (index % 3) * 300,
      y: 80 + Math.floor(index / 3) * 210
    };
    return {
      id: node.id,
      position: basePosition,
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      data: {
        label: nodeLabel(node),
        category: node.category,
        status: node.status
      },
      className: focusNodeIds.has(node.id) ? 'buildgraph-flow-node buildgraph-flow-node--focus' : 'buildgraph-flow-node',
      style: nodeStyle(node)
    } satisfies Node;
  });
  const edges = graph.edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: edge.label,
    type: 'bezier',
    animated: false,
    className: `buildgraph-flow-edge buildgraph-flow-edge--${edge.status.toLowerCase()}`,
    interactionWidth: 20,
    markerEnd: {
      type: MarkerType.ArrowClosed,
      color: statusColor(edge.status),
      width: 18,
      height: 18
    },
    style: {
      stroke: statusColor(edge.status),
      strokeWidth: edge.status === 'PASS' ? 1.75 : 2,
      strokeLinecap: 'round',
      strokeLinejoin: 'round',
      filter: 'drop-shadow(0 1px 2px rgba(15, 23, 42, 0.12))'
    },
    labelStyle: {
      fill: statusColor(edge.status),
      fontSize: 13,
      fontWeight: 800
    },
    labelBgStyle: {
      fill: '#ffffff',
      fillOpacity: 0.86
    },
    labelBgPadding: [10, 5],
    labelBgBorderRadius: 8
  } satisfies Edge));
  return { nodes, edges };
}

function nodeLabel(node: BuildGraphResolveResponse['nodes'][number]) {
  return (
    <div className="buildgraph-node-card buildgraph-node-circle">
      <div className="buildgraph-node-main-label">{node.label}</div>
      <div className={`buildgraph-node-status-label ${statusBadgeTone(node.status)}`}>{statusLabel(node.status)}</div>
    </div>
  );
}

function nodeStyle(node: BuildGraphResolveResponse['nodes'][number]) {
  const status = node.status;
  const diameter = nodeDiameter(node);
  const base = {
    borderRadius: '50%',
    borderWidth: status === 'WARN' ? 4 : 3,
    borderStyle: 'solid',
    padding: 0,
    width: diameter,
    height: diameter,
    minWidth: diameter,
    minHeight: diameter,
    aspectRatio: '1 / 1',
    boxShadow: status === 'WARN'
      ? '0 14px 30px rgba(245, 158, 11, 0.14)'
      : '0 14px 30px rgba(15, 23, 42, 0.08)'
  };
  if (node.type === 'CONSTRAINT') {
    return {
      ...base,
      background: 'linear-gradient(135deg, #f8fafc 0%, #ffffff 100%)',
      borderColor: status === 'PASS' ? '#dbeafe' : status === 'WARN' ? '#f59e0b' : '#ef4444'
    };
  }
  return {
    ...base,
    background: status === 'PASS'
      ? 'linear-gradient(135deg, #ffffff 0%, #f8fbff 100%)'
      : status === 'WARN'
        ? 'linear-gradient(135deg, #ffffff 0%, #fffbeb 100%)'
        : 'linear-gradient(135deg, #ffffff 0%, #fef2f2 100%)',
    borderColor: status === 'PASS' ? '#dbeafe' : status === 'WARN' ? '#f59e0b' : '#ef4444'
  };
}

function nodeDiameter(node: BuildGraphResolveResponse['nodes'][number]) {
  const category = String(node.category ?? node.id).toUpperCase();
  if (category === 'MOTHERBOARD') return 136;
  if (category === 'CASE') return 124;
  if (String(node.label).length >= 8) return 118;
  return DEFAULT_NODE_DIAMETER;
}

function SelectedNodePanel({ node }: { node: BuildGraphNode }) {
  const category = typeof node.category === 'string' && isPartCategory(node.category)
    ? PART_CATEGORY_LABELS[node.category]
    : node.category ?? node.type;
  return (
    <div data-testid="graph-selected-node-detail" className={`mb-4 rounded-lg border p-3 ${statusPanelTone(node.status)}`}>
      <div className="mb-2 flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 text-xs font-black">
          {statusIcon(node.status)}
          선택한 부품 상세
        </div>
        <span className={`rounded px-2 py-1 text-[11px] font-black ${statusBadgeTone(node.status)}`}>{statusLabel(node.status)}</span>
      </div>
      <div className="text-[11px] font-black text-slate-500">{category}</div>
      <div className="mt-1 break-keep text-sm font-black leading-5 text-commerce-ink">{node.label}</div>
      {node.detail ? <p className="mt-2 break-keep text-xs leading-5 text-slate-600">{node.detail}</p> : null}
      {typeof node.price === 'number' ? <div className="mt-2 text-sm font-black text-brand-blue">{node.price.toLocaleString()}원</div> : null}
    </div>
  );
}

function GraphNodeCandidatePanel({
  variant = 'inline',
  activeNode,
  activeNodeCategory,
  candidateContext,
  candidates,
  isLoading,
  isError,
  rejectedCount
}: {
  variant?: 'inline' | 'floating';
  activeNode: BuildGraphNode;
  activeNodeCategory: PartCategory | null;
  candidateContext?: CandidateContext;
  candidates: CompatiblePartCandidate[];
  isLoading: boolean;
  isError: boolean;
  rejectedCount: number;
}) {
  const panelClassName = variant === 'floating'
    ? 'rounded-xl border border-commerce-line bg-white p-4 shadow-2xl'
    : 'border-t border-commerce-line bg-white p-4 shadow-[0_-10px_30px_rgba(15,23,42,0.06)] lg:absolute lg:right-4 lg:top-4 lg:z-10 lg:max-h-[calc(100%-2rem)] lg:w-[330px] lg:overflow-y-auto lg:rounded-xl lg:border lg:shadow-2xl';

  return (
    <div
      data-testid="graph-node-candidate-panel"
      className={panelClassName}
    >
      <SelectedNodePanel node={activeNode} />
      {activeNodeCategory && candidateContext ? (
        <>
          <div className="mb-4 border-t border-dashed border-commerce-line" />
          <CompatibleCandidatesPanel
            candidates={candidates}
            isLoading={isLoading}
            isError={isError}
            rejectedCount={rejectedCount}
            readOnly={Boolean(candidateContext.readOnly)}
            selectedPartIds={candidateContext.selectedPartIds}
            onSelectPart={candidateContext.onSelectPart}
          />
        </>
      ) : null}
    </div>
  );
}

function CompatibleCandidatesPanel({
  candidates,
  isLoading,
  isError,
  rejectedCount,
  readOnly,
  selectedPartIds,
  onSelectPart
}: {
  candidates: CompatiblePartCandidate[];
  isLoading: boolean;
  isError: boolean;
  rejectedCount: number;
  readOnly: boolean;
  selectedPartIds?: Set<string>;
  onSelectPart?: (part: PartRow) => void;
}) {
  const [previewPart, setPreviewPart] = useState<PartRow | null>(null);

  useEffect(() => {
    if (!previewPart) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setPreviewPart(null);
      }
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [previewPart]);

  return (
    <div className="mb-4 rounded-lg border border-commerce-line bg-white p-3">
      <div className="mb-3 flex items-center justify-between gap-3">
        <div>
          <div className="text-sm font-black text-commerce-ink">호환 후보</div>
          <div className="mt-1 text-[11px] font-bold text-slate-500">서버 Tool 검증 기준</div>
        </div>
        {rejectedCount > 0 ? (
          <span className="rounded bg-red-50 px-2 py-1 text-[11px] font-black text-red-700">제외 {rejectedCount}</span>
        ) : null}
      </div>
      {isLoading ? <div className="rounded-md bg-slate-50 p-3 text-xs font-bold text-slate-500">호환 후보를 계산하는 중입니다.</div> : null}
      {isError ? <div className="rounded-md border border-orange-200 bg-orange-50 p-3 text-xs font-bold text-orange-700">호환 후보 API를 불러오지 못했습니다.</div> : null}
      {!isLoading && !isError && candidates.length === 0 ? (
        <div className="rounded-md border border-dashed border-slate-300 p-3 text-xs leading-5 text-slate-500">
          현재 조합 기준으로 보여줄 호환 후보가 없습니다.
        </div>
      ) : null}
      <div className="space-y-2">
        {candidates.map((candidate) => {
          const alreadySelected = Boolean(selectedPartIds?.has(candidate.part.id));
          return (
            <article key={candidate.part.id} className="rounded-md border border-commerce-line bg-slate-50 p-3">
              <div className="flex items-start gap-3">
                <CandidateThumbnail part={candidate.part} onPreview={setPreviewPart} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="line-clamp-2 text-xs font-black leading-5 text-commerce-ink">{candidate.part.name}</div>
                      <div className="mt-1 text-[11px] font-bold text-slate-500">
                        {(candidate.part.manufacturer ?? '제조사 미상')} · {candidate.part.price.toLocaleString()}원
                      </div>
                    </div>
                    <span className={`shrink-0 rounded px-2 py-1 text-[10px] font-black ${statusBadgeTone(candidate.status)}`}>
                      {candidate.statusLabel || statusLabel(candidate.status)}
                    </span>
                  </div>
                  <p className="mt-2 break-keep text-[11px] leading-5 text-slate-600">{candidate.summary}</p>
                  <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
                    <span className="text-[10px] font-black uppercase tracking-wide text-slate-400">{candidate.checkedTools.join(' · ') || 'ACTIVE'}</span>
                    {readOnly ? (
                      <span className="rounded bg-slate-200 px-2 py-1 text-[11px] font-black text-slate-600">읽기 전용</span>
                    ) : (
                      <button
                        type="button"
                        aria-label={`${candidate.part.name} 담기/교체`}
                        disabled={alreadySelected}
                        onClick={() => onSelectPart?.(candidate.part)}
                        className="rounded bg-commerce-ink px-3 py-1.5 text-[11px] font-black text-white hover:bg-slate-700 disabled:bg-slate-300"
                      >
                        {alreadySelected ? '담김' : '담기/교체'}
                      </button>
                    )}
                  </div>
                </div>
              </div>
            </article>
          );
        })}
      </div>
      {previewPart ? <PartImagePreviewDialog part={previewPart} onClose={() => setPreviewPart(null)} /> : null}
    </div>
  );
}

function CandidateThumbnail({ part, onPreview }: { part: PartRow; onPreview: (part: PartRow) => void }) {
  const imageUrl = partPhotoUrl(part);
  const categoryLabel = part.category === 'STORAGE' ? 'SSD' : part.category;
  if (!imageUrl) {
    return (
      <div
        role="img"
        aria-label={`${part.name} 사진 없음`}
        className="grid h-16 w-16 shrink-0 place-items-center rounded-md border border-dashed border-slate-300 bg-white text-[11px] font-black text-slate-400"
      >
        {categoryLabel}
      </div>
    );
  }
  return (
    <button
      type="button"
      aria-label={`${part.name} 사진 확대`}
      onClick={() => onPreview(part)}
      className="h-16 w-16 shrink-0 overflow-hidden rounded-md border border-commerce-line bg-white p-1 transition hover:border-brand-blue hover:shadow-product focus:outline-none focus:ring-2 focus:ring-brand-blue"
    >
      <img src={imageUrl} alt={`${part.name} 제품 사진`} className="h-full w-full object-contain" />
    </button>
  );
}

function PartImagePreviewDialog({ part, onClose }: { part: PartRow; onClose: () => void }) {
  const imageUrl = partPhotoUrl(part);
  if (!imageUrl) return null;
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`${part.name} 사진 확대`}
      className="fixed inset-0 z-[80] grid place-items-center bg-slate-950/70 p-4"
      onMouseDown={onClose}
    >
      <div
        className="w-full max-w-3xl rounded-xl border border-white/10 bg-white p-4 shadow-2xl"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="mb-3 flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="line-clamp-2 text-base font-black text-commerce-ink">{part.name}</div>
            <div className="mt-1 text-sm font-bold text-slate-500">
              {(part.manufacturer ?? '제조사 미상')} · {part.price.toLocaleString()}원
            </div>
          </div>
          <button
            type="button"
            aria-label="사진 확대 닫기"
            onClick={onClose}
            className="grid h-9 w-9 shrink-0 place-items-center rounded-md border border-commerce-line bg-white text-slate-600 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-brand-blue"
          >
            <X size={17} />
          </button>
        </div>
        <div className="grid max-h-[72vh] place-items-center rounded-lg border border-commerce-line bg-slate-50 p-4">
          <img src={imageUrl} alt={`${part.name} 확대 이미지`} className="max-h-[65vh] w-full object-contain" />
        </div>
      </div>
    </div>
  );
}

function partPhotoUrl(part: PartRow) {
  const externalImageUrl = part.externalOffer?.imageUrl;
  if (typeof externalImageUrl === 'string' && externalImageUrl.trim()) {
    return externalImageUrl;
  }
  const attributeImageUrl = part.attributes?.imageUrl;
  if (typeof attributeImageUrl === 'string' && attributeImageUrl.trim()) {
    return attributeImageUrl;
  }
  return null;
}

function GraphStat({ label, value, tone = 'default' }: { label: string; value: number; tone?: 'default' | 'warn' }) {
  return (
    <div className="rounded-md border border-commerce-line bg-slate-50 p-2">
      <div className={`font-black ${tone === 'warn' && value > 0 ? 'text-amber-600' : 'text-commerce-ink'}`}>{value}</div>
      <div className="mt-0.5 text-slate-500">{label}</div>
    </div>
  );
}

function statusIcon(status: BuildGraphStatus) {
  if (status === 'PASS') return <CheckCircle2 size={14} className="text-commerce-green" />;
  if (status === 'WARN') return <AlertTriangle size={14} className="text-amber-600" />;
  return <AlertTriangle size={14} className="text-red-600" />;
}

function statusColor(status: string) {
  if (status === 'FAIL') return '#dc2626';
  if (status === 'WARN') return '#d97706';
  return '#2563eb';
}

function statusPanelTone(status: BuildGraphStatus) {
  if (status === 'FAIL') return 'border-red-200 bg-red-50';
  if (status === 'WARN') return 'border-amber-200 bg-amber-50';
  return 'border-blue-100 bg-blue-50';
}

function statusBadgeTone(status: BuildGraphStatus) {
  if (status === 'FAIL') return 'bg-red-100 text-red-700';
  if (status === 'WARN') return 'bg-amber-100 text-amber-700';
  return 'bg-emerald-50 text-emerald-700';
}

function statusLabel(status: BuildGraphStatus) {
  if (status === 'FAIL') return '장착 불가';
  if (status === 'WARN') return '간섭 주의';
  return '호환됨';
}

function isPartCategory(value: string): value is PartCategory {
  return Object.keys(PART_CATEGORY_LABELS).includes(value);
}
