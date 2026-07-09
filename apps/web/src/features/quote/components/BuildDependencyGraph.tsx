import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
  type ReactFlowInstance
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { AlertTriangle, CheckCircle2, GitBranch, Info, Maximize2, X } from 'lucide-react';
import { useHiddenPageScrollbar } from '../../../hooks/useHiddenPageScrollbar';
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
  isRefreshing?: boolean;
  isError?: boolean;
  variant?: 'default' | 'preview';
  totalPrice?: number;
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
type BuildGraphInsight = BuildGraphResolveResponse['insights'][number];
type GraphLayoutVariant = 'default' | 'preview';

const DEFAULT_NODE_SIZE = { width: 220, height: 108 };
const WIDE_NODE_SIZE = { width: 250, height: 112 };
const PRICE_NODE_SIZE = { width: 220, height: 88 };
const NODE_COLLISION_GAP = 32;
const NODE_POSITION_OFFSETS = [
  { x: 0, y: 0 },
  { x: 300, y: 0 },
  { x: 0, y: 150 },
  { x: 300, y: 150 },
  { x: -300, y: 0 },
  { x: 0, y: -150 },
  { x: 300, y: -150 },
  { x: -300, y: 150 },
  { x: 600, y: 0 },
  { x: 600, y: 150 },
  { x: -300, y: -150 }
];
const FLOATING_GRAPH_DEFAULT_SIZE = { width: 500, graphHeight: 480 };
const FLOATING_GRAPH_MIN_SIZE = { width: 300, graphHeight: 200 };
const FLOATING_GRAPH_MAX_SIZE = { width: 760, graphHeight: 640 };
const FLOATING_GRAPH_MAIN_VISIBLE_RATIO = 0.5;
const FLOATING_GRAPH_VIEWPORT_MARGIN = 40;
const FLOATING_GRAPH_HEADER_HEIGHT = 42;
const graphNodeTypes = { graphCard: GraphCardNode, priceTotal: PriceTotalNode };
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
const previewNodePositions = [
  { x: 340, y: 240 },
  { x: 55, y: 190 },
  { x: 625, y: 190 },
  { x: 340, y: 42 },
  { x: 340, y: 438 },
  { x: 55, y: 420 },
  { x: 625, y: 420 },
  { x: 55, y: 44 },
  { x: 625, y: 44 },
  { x: 55, y: 610 },
  { x: 625, y: 610 }
];
const previewPricePositions = [
  { x: 340, y: 620 },
  { x: 55, y: 620 },
  { x: 625, y: 620 }
];
const previewCategoryPositions: Record<string, { x: number; y: number }> = {
  CPU: { x: 55, y: 170 },
  MOTHERBOARD: { x: 340, y: 210 },
  RAM: { x: 625, y: 80 },
  GPU: { x: 55, y: 330 },
  PSU: { x: 625, y: 330 },
  CASE: { x: 340, y: 455 },
  COOLER: { x: 55, y: 495 },
  STORAGE: { x: 55, y: 62 },
  PRICE: { x: 625, y: 500 }
};

export function BuildDependencyGraph({
  graph,
  isLoading,
  isRefreshing = false,
  isError,
  variant = 'default',
  totalPrice,
  title = '견적 관계도',
  subtitle = '선택한 부품이 전력, 규격, 호환성에 주는 영향을 시각화합니다.',
  onCategorySelect,
  candidateContext
}: BuildDependencyGraphProps) {
  useHiddenPageScrollbar();

  const [activeEdge, setActiveEdge] = useState<BuildGraphEdge | null>(null);
  const [activeNodeId, setActiveNodeId] = useState<string | null>(null);
  const [isEdgeGuideVisible, setIsEdgeGuideVisible] = useState(true);
  const [isLegendExpanded, setIsLegendExpanded] = useState(true);
  const [issueFocusNodeIds, setIssueFocusNodeIds] = useState<Set<string>>(new Set());
  const [isDesktopViewport, setIsDesktopViewport] = useState(false);
  const [showFloatingGraph, setShowFloatingGraph] = useState(false);
  const [previewNodePositionOverrides, setPreviewNodePositionOverrides] = useState<Record<string, { x: number; y: number }>>({});
  const mainFlowInstanceRef = useRef<ReactFlowInstance | null>(null);
  const graphCanvasRef = useRef<HTMLDivElement | null>(null);
  const hasSeenGraphRef = useRef(false);
  const issueFocusTimeoutRef = useRef<number | null>(null);
  const isPreviewVariant = variant === 'preview';
  const displayGraph = useMemo(() => withDisplayTotalPrice(graph, totalPrice), [graph, totalPrice]);
  const graphModel = useMemo(() => buildGraphDisplayModel(displayGraph), [displayGraph]);
  const issueInsight = useMemo(() => selectRepresentativeIssue(displayGraph), [displayGraph]);
  const activeNode = graphModel.nodes.find((node) => node.id === activeNodeId) ?? null;
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
  const flowElements = useMemo(
    () => toFlowElements(displayGraph, graphModel.nodes, graphModel.edges, isPreviewVariant ? 'preview' : 'default'),
    [displayGraph, graphModel, isPreviewVariant]
  );
  const flowNodePositionSignature = useMemo(
    () => flowElements.nodes.map((node) => `${node.id}:${Math.round(node.position.x)}:${Math.round(node.position.y)}`).join('|'),
    [flowElements.nodes]
  );
  const nodes = useMemo<Node[]>(() => flowElements.nodes.map((node) => {
    const originalNodeId = typeof node.data.originalId === 'string' ? node.data.originalId : String(node.id);
    const previewPosition = isPreviewVariant ? previewNodePositionOverrides[String(node.id)] : undefined;
    return {
      ...node,
      position: previewPosition ?? node.position,
      className: [
        node.className,
        issueFocusNodeIds.has(originalNodeId) ? 'buildgraph-flow-node--issue-focus' : ''
      ].filter(Boolean).join(' ')
    };
  }), [flowElements.nodes, isPreviewVariant, issueFocusNodeIds, previewNodePositionOverrides]);
  const edges = flowElements.edges;
  const canShowFloatingGraph = !isPreviewVariant && Boolean(displayGraph && graphModel.nodes.length > 0 && !isLoading && !isError);
  const canShowGraphOverlays = !isPreviewVariant;

  useEffect(() => {
    return () => {
      if (issueFocusTimeoutRef.current !== null) {
        window.clearTimeout(issueFocusTimeoutRef.current);
      }
    };
  }, []);

  useEffect(() => {
    if (isPreviewVariant) {
      setPreviewNodePositionOverrides({});
    }
  }, [flowNodePositionSignature, isPreviewVariant]);

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
        if (entry.intersectionRatio >= FLOATING_GRAPH_MAIN_VISIBLE_RATIO) {
          hasSeenGraphRef.current = true;
          setShowFloatingGraph(false);
          return;
        }
        setShowFloatingGraph(hasSeenGraphRef.current);
      },
      { threshold: [0, 0.18, FLOATING_GRAPH_MAIN_VISIBLE_RATIO, 1] }
    );
    observer.observe(graphElement);
    return () => observer.disconnect();
  }, [canShowFloatingGraph, isDesktopViewport]);

  const handleNodeClick = (node: Node) => {
    // 노드 클릭은 후보 패널만 연다. 카테고리 전환(목록 페이지 리셋)은 패널의 명시적 버튼으로 분리한다.
    const originalNodeId = typeof node.data.originalId === 'string' ? node.data.originalId : String(node.id);
    setActiveNodeId(originalNodeId);
    setActiveEdge(null);
  };

  const handleEdgeClick = (edge: Edge) => {
    const graphEdge = displayGraph?.edges.find((item) => item.id === edge.id);
    setActiveNodeId(null);
    setActiveEdge(graphEdge ?? null);
    setIsEdgeGuideVisible(true);
  };

  const handlePreviewNodeDragStop = (_event: unknown, node: Node) => {
    if (!isPreviewVariant) return;
    setPreviewNodePositionOverrides((current) => ({
      ...current,
      [String(node.id)]: { x: node.position.x, y: node.position.y }
    }));
  };

  const focusIssueNodes = () => {
    if (!issueInsight) return;
    const relatedNodeIds = new Set(issueInsight.relatedNodeIds.map(String));
    setIssueFocusNodeIds(relatedNodeIds);
    const targetFlowNodes = nodes.filter((node) => {
      const originalNodeId = typeof node.data.originalId === 'string' ? node.data.originalId : String(node.id);
      return relatedNodeIds.has(originalNodeId);
    });
    if (targetFlowNodes.length > 0) {
      mainFlowInstanceRef.current?.fitView({
        nodes: targetFlowNodes.map((node) => ({ id: node.id })),
        padding: 0.28,
        duration: 360
      });
    }
    if (issueFocusTimeoutRef.current !== null) {
      window.clearTimeout(issueFocusTimeoutRef.current);
    }
    issueFocusTimeoutRef.current = window.setTimeout(() => {
      setIssueFocusNodeIds(new Set());
      issueFocusTimeoutRef.current = null;
    }, 2200);
  };

  const scrollToMainGraph = () => {
    graphCanvasRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  return (
    <section data-testid="build-dependency-graph" className={`panel overflow-hidden ${isPreviewVariant ? 'buildgraph-preview-mode' : ''}`}>
      <div className="buildgraph-header flex flex-col gap-4 border-b border-commerce-line bg-white px-5 py-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <div className="flex items-center gap-2 text-xs font-black text-brand-blue">
            <GitBranch size={15} />
            호환 관계
          </div>
          <h2 className="mt-1 text-xl font-black text-commerce-ink">{title}</h2>
          <p className="mt-1 max-w-3xl break-keep text-sm leading-6 text-slate-500">{displayGraph?.summary ?? subtitle}</p>
        </div>
        <div className="flex w-full flex-col gap-2 lg:w-auto">
          {displayGraph?.compositeScore ? <CompositeScoreMeter score={displayGraph.compositeScore} /> : null}
          <div className="buildgraph-stat-grid grid grid-cols-3 gap-2 text-center text-xs sm:min-w-[260px]">
            <GraphStat label="노드" value={displayGraph ? graphModel.nodes.length : 0} />
            <GraphStat label="관계" value={displayGraph ? graphModel.edges.length : 0} />
            <GraphStat label="주의" value={displayGraph?.insights.filter((insight) => insight.status !== 'PASS').length ?? 0} tone="warn" />
          </div>
        </div>
      </div>

      {isLoading && !displayGraph ? (
        <div className="grid h-[430px] place-items-center border-b border-commerce-line bg-[linear-gradient(180deg,#f8fafc_0%,#ffffff_100%)] p-6 text-sm font-bold text-slate-500 lg:h-[680px] xl:h-[720px]">
          관계 그래프를 계산하는 중입니다.
        </div>
      ) : isError && !displayGraph ? (
        <div className="m-5 rounded-lg border border-orange-200 bg-orange-50 p-5 text-sm font-bold text-orange-700">
          관계 그래프 API를 불러오지 못했습니다.
        </div>
      ) : !displayGraph || graphModel.nodes.length === 0 ? (
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
        <div className="buildgraph-content">
          <div
            ref={graphCanvasRef}
            data-testid="graph-flow-canvas"
            data-active-edge-id={activeEdge?.id}
            className="buildgraph-canvas relative min-w-0 border-b border-commerce-line bg-[linear-gradient(180deg,#f8fafc_0%,#ffffff_100%)] lg:border-b-0"
          >
            {isEdgeGuideVisible && !activeNode ? (
              <GraphEdgeGuideCapsule
                edge={activeEdge}
                onClose={() => setIsEdgeGuideVisible(false)}
              />
            ) : null}

            {canShowGraphOverlays && issueInsight ? (
              <GraphIssueCard
                insight={issueInsight}
                onFocusIssue={focusIssueNodes}
              />
            ) : null}

            <div className={`buildgraph-flow-area ${isPreviewVariant ? 'h-full min-h-0 p-0' : `h-[520px] ${issueInsight ? 'pt-0 lg:pt-[184px]' : 'pt-[96px] lg:pt-[88px]'} lg:h-[calc(100vh-180px)] xl:h-[calc(100vh-160px)]`}`}>
              <div className="h-full">
                <ReactFlow
                  className={isPreviewVariant ? 'buildgraph-preview-flow' : 'buildgraph-scroll-pass-through'}
                  nodes={nodes}
                  edges={edges}
                  nodeTypes={graphNodeTypes}
                  fitView
                  fitViewOptions={{ padding: 0.06 }}
                  minZoom={0.45}
                  maxZoom={1.35}
                  preventScrolling={isPreviewVariant}
                  zoomOnScroll={isPreviewVariant}
                  zoomOnPinch={isPreviewVariant}
                  panOnScroll={false}
                  panOnDrag={isPreviewVariant}
                  nodesDraggable={isPreviewVariant}
                  nodesConnectable={false}
                  proOptions={{ hideAttribution: true }}
                  onInit={(instance) => {
                    mainFlowInstanceRef.current = instance;
                  }}
                  onNodeClick={(_, node: Node) => handleNodeClick(node)}
                  onNodeDragStop={handlePreviewNodeDragStop}
                  onEdgeClick={(_, edge: Edge) => handleEdgeClick(edge)}
                  onPaneClick={() => {
                    // 빈 캔버스를 클릭하면 노드/엣지 선택과 후보 패널을 해제해 선택을 취소할 수 있게 한다.
                    setActiveNodeId(null);
                    setActiveEdge(null);
                    setIsEdgeGuideVisible(false);
                  }}
                >
                  <Background color="#dbe4f0" gap={18} />
                  {isDesktopViewport ? <Controls showInteractive={false} /> : null}
                </ReactFlow>
              </div>
            </div>
            {isRefreshing ? (
              <div className="absolute left-1/2 top-4 z-10 -translate-x-1/2 rounded-full border border-blue-100 bg-white/95 px-3 py-1.5 text-xs font-black text-brand-blue shadow-product">
                관계도 업데이트 중
              </div>
            ) : null}

            {canShowGraphOverlays && !activeNode ? (
              <GraphEdgeLegendCard
                isExpanded={isLegendExpanded}
                onToggle={() => setIsLegendExpanded((current) => !current)}
              />
            ) : null}

            {activeNode ? (
              <GraphNodeCandidatePanel
                activeNode={activeNode}
                activeNodeCategory={activeNodeCategory}
                candidateContext={candidateContext}
                candidates={candidateQuery.data?.items ?? []}
                isLoading={candidateQuery.isLoading}
                isError={candidateQuery.isError}
                rejectedCount={candidateQuery.data?.rejectedCount ?? 0}
                onViewCategoryList={activeNodeCategory && onCategorySelect ? () => onCategorySelect(activeNodeCategory) : undefined}
              />
            ) : null}
          </div>
        </div>
      )}
      {showFloatingGraph && canShowFloatingGraph ? (
        <FloatingDependencyGraph
          nodes={nodes}
          edges={edges}
          onScrollToMainGraph={scrollToMainGraph}
        />
      ) : null}
    </section>
  );
}

function FloatingDependencyGraph({
  nodes,
  edges,
  onScrollToMainGraph
}: {
  nodes: Node[];
  edges: Edge[];
  onScrollToMainGraph: () => void;
}) {
  const [size, setSize] = useState(FLOATING_GRAPH_DEFAULT_SIZE);
  const [isResizing, setIsResizing] = useState(false);
  const [miniActiveNodeId, setMiniActiveNodeId] = useState<string | null>(null);
  const flowInstanceRef = useRef<ReactFlowInstance | null>(null);
  const resizeFrameRef = useRef<number | null>(null);
  const dragRef = useRef<{
    startX: number;
    startY: number;
    startWidth: number;
    startGraphHeight: number;
    maxWidth: number;
    maxGraphHeight: number;
  } | null>(null);

  const fitFloatingGraph = useCallback(() => {
    if (resizeFrameRef.current !== null) {
      window.cancelAnimationFrame(resizeFrameRef.current);
    }
    resizeFrameRef.current = window.requestAnimationFrame(() => {
      resizeFrameRef.current = null;
      flowInstanceRef.current?.fitView({ padding: 0.18, duration: 80 });
    });
  }, []);

  useEffect(() => {
    fitFloatingGraph();
  }, [fitFloatingGraph, size.width, size.graphHeight, nodes.length, edges.length]);

  useEffect(() => {
    return () => {
      if (resizeFrameRef.current !== null) {
        window.cancelAnimationFrame(resizeFrameRef.current);
      }
    };
  }, []);

  useEffect(() => {
    const clampSizeToViewport = () => {
      setSize((current) => clampFloatingGraphSize(current));
    };
    clampSizeToViewport();
    window.addEventListener('resize', clampSizeToViewport);
    return () => window.removeEventListener('resize', clampSizeToViewport);
  }, []);

  useEffect(() => {
    if (miniActiveNodeId && !nodes.some((node) => node.id === miniActiveNodeId)) {
      setMiniActiveNodeId(null);
    }
  }, [miniActiveNodeId, nodes]);

  const floatingNodes = useMemo<Node[]>(() => nodes.map((node) => ({
    ...node,
    className: [
      node.className,
      node.id === miniActiveNodeId ? 'buildgraph-flow-node--mini-active' : ''
    ].filter(Boolean).join(' ')
  })), [miniActiveNodeId, nodes]);

  useEffect(() => {
    if (!isResizing) return;

    const previousCursor = document.body.style.cursor;
    const previousUserSelect = document.body.style.userSelect;
    document.body.style.cursor = 'nesw-resize';
    document.body.style.userSelect = 'none';

    const handlePointerMove = (event: PointerEvent) => {
      const drag = dragRef.current;
      if (!drag) return;
      const nextWidth = clamp(
        drag.startWidth + event.clientX - drag.startX,
        FLOATING_GRAPH_MIN_SIZE.width,
        drag.maxWidth
      );
      const nextGraphHeight = clamp(
        drag.startGraphHeight - (event.clientY - drag.startY),
        FLOATING_GRAPH_MIN_SIZE.graphHeight,
        drag.maxGraphHeight
      );
      setSize({ width: nextWidth, graphHeight: nextGraphHeight });
    };

    const stopResize = () => {
      dragRef.current = null;
      setIsResizing(false);
    };

    window.addEventListener('pointermove', handlePointerMove);
    window.addEventListener('pointerup', stopResize);
    window.addEventListener('pointercancel', stopResize);

    return () => {
      document.body.style.cursor = previousCursor;
      document.body.style.userSelect = previousUserSelect;
      window.removeEventListener('pointermove', handlePointerMove);
      window.removeEventListener('pointerup', stopResize);
      window.removeEventListener('pointercancel', stopResize);
    };
  }, [isResizing]);

  const startResize = (event: ReactPointerEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    const maxSize = floatingGraphMaxSize();
    dragRef.current = {
      startX: event.clientX,
      startY: event.clientY,
      startWidth: size.width,
      startGraphHeight: size.graphHeight,
      maxWidth: maxSize.width,
      maxGraphHeight: maxSize.graphHeight
    };
    setIsResizing(true);
  };

  return (
    <div className="fixed bottom-5 left-5 z-[70] hidden max-w-[calc(100vw-2.5rem)] flex-col items-start gap-3 lg:flex">
      <div
        data-testid="floating-dependency-graph"
        className={`relative shrink-0 overflow-hidden rounded-xl border border-commerce-line bg-white shadow-2xl ${isResizing ? 'ring-2 ring-brand-blue/30' : ''}`}
        style={{ width: size.width }}
      >
        <button
          type="button"
          data-testid="floating-graph-resize-handle"
          aria-label="미니 관계도 크기 조절"
          title="우측 상단을 드래그해 크기 조절"
          onPointerDown={startResize}
          className="absolute right-2 top-2 z-20 grid h-7 w-7 cursor-nesw-resize touch-none place-items-center rounded-full border border-blue-200 bg-white text-brand-blue shadow-product transition hover:border-brand-blue hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-brand-blue"
        >
          <Maximize2 size={14} className="-rotate-45" />
        </button>
        <div className="flex items-center justify-between gap-3 border-b border-commerce-line px-3 py-2 pr-11">
          <div className="min-w-0">
            <div className="text-[11px] font-black text-brand-blue">호환 관계</div>
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
        <div
          className="bg-[linear-gradient(180deg,#f8fafc_0%,#ffffff_100%)]"
          style={{ height: size.graphHeight }}
        >
          <ReactFlow
            className="buildgraph-scroll-pass-through"
            nodes={floatingNodes}
            edges={edges}
            nodeTypes={graphNodeTypes}
            minZoom={0.18}
            maxZoom={1.15}
            preventScrolling={false}
            zoomOnScroll={false}
            zoomOnPinch={false}
            panOnScroll={false}
            panOnDrag={false}
            nodesDraggable={false}
            nodesConnectable={false}
            proOptions={{ hideAttribution: true }}
            onInit={(instance) => {
              flowInstanceRef.current = instance;
              fitFloatingGraph();
            }}
            onNodeClick={(_, node: Node) => setMiniActiveNodeId(node.id)}
          >
            <Background color="#dbe4f0" gap={14} />
            <Controls showInteractive={false} />
          </ReactFlow>
        </div>
      </div>
    </div>
  );
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

function floatingGraphMaxSize() {
  const viewportWidth = typeof window === 'undefined' ? 1280 : window.innerWidth;
  const viewportHeight = typeof window === 'undefined' ? 720 : window.innerHeight;
  const reservedHeight = 120;

  return {
    width: Math.max(
      FLOATING_GRAPH_MIN_SIZE.width,
      Math.min(FLOATING_GRAPH_MAX_SIZE.width, viewportWidth - FLOATING_GRAPH_VIEWPORT_MARGIN)
    ),
    graphHeight: Math.max(
      FLOATING_GRAPH_MIN_SIZE.graphHeight,
      Math.min(FLOATING_GRAPH_MAX_SIZE.graphHeight, viewportHeight - reservedHeight)
    )
  };
}

function clampFloatingGraphSize(size: typeof FLOATING_GRAPH_DEFAULT_SIZE) {
  const maxSize = floatingGraphMaxSize();
  return {
    width: clamp(size.width, FLOATING_GRAPH_MIN_SIZE.width, maxSize.width),
    graphHeight: clamp(size.graphHeight, FLOATING_GRAPH_MIN_SIZE.graphHeight, maxSize.graphHeight)
  };
}

function GraphCardNode({ data }: NodeProps<Node<{ label: ReactNode }>>) {
  return (
    <>
      <Handle type="target" position={Position.Left} />
      {data.label}
      <Handle type="source" position={Position.Right} />
    </>
  );
}

function PriceTotalNode({ data }: NodeProps<Node<{ label: ReactNode }>>) {
  return <>{data.label}</>;
}

function GraphEdgeGuideCapsule({
  edge,
  onClose
}: {
  edge: BuildGraphEdge | null;
  onClose: () => void;
}) {
  const isRelationshipDetail = Boolean(edge);
  const panelTone = edge ? statusPanelTone(edge.status) : 'border-blue-100 bg-white/95';

  return (
    <div
      data-testid="graph-edge-guide-capsule"
      onPointerDown={(event) => event.stopPropagation()}
      className={`relative z-20 mx-3 mt-3 rounded-xl border px-3 py-2.5 shadow-product backdrop-blur lg:absolute lg:left-1/2 lg:right-auto lg:top-3 lg:m-0 lg:w-[640px] lg:max-w-[calc(100%-2rem)] lg:-translate-x-1/2 ${panelTone}`}
    >
      <div className="flex min-w-0 items-start gap-2.5">
        <div className="mt-0.5 grid h-6 w-6 shrink-0 place-items-center rounded-full bg-white text-brand-blue shadow-product">
          {edge ? statusIcon(edge.status) : <Info size={14} />}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex min-w-0 items-center gap-2">
            <div className="shrink-0 text-[11px] font-black text-commerce-ink">
              {isRelationshipDetail ? '선택한 관계' : '관계 안내'}
            </div>
            {edge ? (
              <span className={`shrink-0 rounded px-2 py-0.5 text-[10px] font-black ${statusBadgeTone(edge.status)}`}>
                {statusLabel(edge.status)}
              </span>
            ) : null}
          </div>
          {edge ? (
            <>
              <div className="mt-1 truncate text-sm font-black text-commerce-ink" title={edge.label}>
                {edge.label}
              </div>
              <p className="mt-0.5 break-keep text-xs font-bold leading-5 text-slate-600">
                {edge.summary}
              </p>
            </>
          ) : (
            <p className="mt-0.5 break-keep text-xs font-bold leading-5 text-slate-600">
              선을 누르면 두 부품 사이의 제약과 판단 근거를 확인할 수 있습니다
            </p>
          )}
        </div>
        <button
          type="button"
          aria-label="관계 안내 닫기"
          onClick={onClose}
          className="grid h-7 w-7 shrink-0 place-items-center rounded-md text-slate-500 transition hover:bg-white hover:text-commerce-ink focus:outline-none focus:ring-2 focus:ring-brand-blue"
        >
          <X size={15} />
        </button>
      </div>
    </div>
  );
}

function GraphIssueCard({
  insight,
  onFocusIssue
}: {
  insight: BuildGraphInsight;
  onFocusIssue: () => void;
}) {
  const relatedNodeCount = insight.relatedNodeIds.length;
  const hasRelatedNodes = relatedNodeCount > 0;

  return (
    <article
      data-testid="graph-issue-card"
      onPointerDown={(event) => event.stopPropagation()}
      className={`relative z-20 mx-3 mt-4 rounded-xl border p-3 shadow-product backdrop-blur sm:mr-auto sm:w-[330px] lg:absolute lg:left-3 lg:right-auto lg:top-[92px] lg:m-0 ${statusPanelTone(insight.status)}`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2 text-xs font-black">
          {statusIcon(insight.status)}
          <span>{issueCardTitle(insight.status)}</span>
        </div>
        <span className="shrink-0 text-[11px] font-black text-slate-500">
          {relatedNodeCount}개 노드
        </span>
      </div>
      <div className="mt-2 line-clamp-1 text-sm font-black text-commerce-ink" title={insight.title}>
        {insight.title}
      </div>
      <p className="mt-1 break-keep text-xs font-bold leading-5 text-slate-600">
        {insight.description}
      </p>
      <button
        type="button"
        disabled={!hasRelatedNodes}
        onClick={onFocusIssue}
        className="mt-3 rounded-md px-0 py-1 text-xs font-black text-commerce-sale transition hover:text-red-700 focus:outline-none focus:ring-2 focus:ring-brand-blue disabled:text-slate-400"
      >
        문제 노드로 이동
      </button>
    </article>
  );
}

function GraphEdgeLegendCard({
  isExpanded,
  onToggle
}: {
  isExpanded: boolean;
  onToggle: () => void;
}) {
  return (
    <div
      data-testid="graph-edge-legend-card"
      onPointerDown={(event) => event.stopPropagation()}
      className="mb-3 ml-3 mr-24 rounded-xl border border-commerce-line bg-white/95 p-3 shadow-product backdrop-blur lg:absolute lg:bottom-4 lg:right-24 lg:z-10 lg:m-0 lg:w-[310px]"
    >
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2 text-xs font-black text-commerce-ink">
          <Info size={14} className="shrink-0 text-slate-500" />
          <span className="truncate">그래프 읽는 법</span>
        </div>
        <button
          type="button"
          aria-label={isExpanded ? '그래프 읽는 법 접기' : '그래프 읽는 법 펼치기'}
          onClick={onToggle}
          className="shrink-0 rounded-md px-2 py-1 text-[11px] font-black text-slate-500 transition hover:bg-slate-50 hover:text-commerce-ink focus:outline-none focus:ring-2 focus:ring-brand-blue"
        >
          {isExpanded ? '접기' : '펼치기'}
        </button>
      </div>
      {isExpanded ? (
        <div className="mt-3 space-y-2">
          <LegendLine color="#2563eb" label="파란 선" description="선택이 영향을 주는 관계" />
          <LegendLine color="#d97706" label="노란 선" description="확인이 필요한 관계" />
          <LegendLine color="#dc2626" label="빨간 선" description="교체 후보를 먼저 봐야 하는 관계" />
        </div>
      ) : null}
    </div>
  );
}

function LegendLine({
  color,
  label,
  description
}: {
  color: string;
  label: string;
  description: string;
}) {
  return (
    <div className="flex items-center gap-2 text-xs leading-5">
      <span className="relative h-0.5 w-9 shrink-0 rounded-full" style={{ backgroundColor: color }}>
        <span
          className="absolute right-0 top-1/2 h-2 w-2 -translate-y-1/2 rotate-45 border-r-2 border-t-2"
          style={{ borderColor: color }}
        />
      </span>
      <span className="shrink-0 font-black text-commerce-ink">{label}</span>
      <span className="min-w-0 break-keep font-bold text-slate-500">{description}</span>
    </div>
  );
}

function buildGraphDisplayModel(graph?: BuildGraphResolveResponse | null) {
  if (!graph) {
    return { nodes: [], edges: [], validationNodes: [] };
  }

  const canvasNodes = graph.nodes.filter(isGraphCanvasNode);
  const nodeStatusById = new Map(canvasNodes.map((node) => [String(node.id), node.status]));
  const visibleNodeIds = new Set(canvasNodes.map((node) => String(node.id)));

  const applyStatus = (nodeId: unknown, status: BuildGraphStatus) => {
    const key = String(nodeId);
    if (!visibleNodeIds.has(key)) return;
    nodeStatusById.set(key, worstStatus(nodeStatusById.get(key) ?? 'PASS', status));
  };

  for (const edge of graph.edges) {
    if (edge.status === 'PASS') continue;
    applyStatus(edge.source, edge.status);
    applyStatus(edge.target, edge.status);
  }

  for (const insight of graph.insights) {
    if (insight.status === 'PASS') continue;
    for (const nodeId of insight.relatedNodeIds) {
      applyStatus(nodeId, insight.status);
    }
  }

  for (const validationNode of graph.nodes.filter(isValidationSummaryNode)) {
    if (validationNode.status === 'PASS') continue;
    const category = typeof validationNode.category === 'string' ? validationNode.category.toUpperCase() : '';
    for (const node of canvasNodes) {
      if (typeof node.category === 'string' && node.category.toUpperCase() === category) {
        applyStatus(node.id, validationNode.status);
      }
    }
  }

  const nodes = canvasNodes.map((node) => {
    const status = nodeStatusById.get(String(node.id)) ?? node.status;
    return status === node.status ? node : { ...node, status };
  });
  const visibleFlowNodeIds = new Set(nodes.map((node) => node.id));
  const edges = graph.edges.filter((edge) => (
    visibleFlowNodeIds.has(String(edge.source)) && visibleFlowNodeIds.has(String(edge.target))
  ));
  const validationNodes = graph.nodes.filter(isValidationSummaryNode);

  return { nodes, edges, validationNodes };
}

function worstStatus(left: BuildGraphStatus, right: BuildGraphStatus): BuildGraphStatus {
  const rank: Record<BuildGraphStatus, number> = { PASS: 0, WARN: 1, FAIL: 2 };
  return rank[right] > rank[left] ? right : left;
}

function selectRepresentativeIssue(graph?: BuildGraphResolveResponse | null) {
  if (!graph) return null;
  return graph.insights.find((insight) => insight.status === 'FAIL')
    ?? graph.insights.find((insight) => insight.status === 'WARN')
    ?? null;
}

function issueCardTitle(status: BuildGraphStatus) {
  if (status === 'FAIL') return '장착 불가';
  if (status === 'WARN') return '주의 필요';
  return '검증 통과';
}

function isGraphCanvasNode(node: BuildGraphNode) {
  return node.type === 'PART' || isTotalPriceGraphNode(node);
}

function isValidationSummaryNode(node: BuildGraphNode) {
  return node.type === 'CONSTRAINT'
    && !isPriceGraphNode(node)
    && !isBudgetGraphNode(node);
}

function isTotalPriceGraphNode(node: Pick<BuildGraphNode, 'category' | 'type' | 'id' | 'label'>) {
  return isPriceGraphNode(node) && !isBudgetGraphNode(node);
}

function isBudgetGraphNode(node: Pick<BuildGraphNode, 'id' | 'label'>) {
  const id = String(node.id ?? '').toLowerCase();
  const label = String(node.label ?? '').trim();
  return id.includes('budget') || label.includes('예산');
}

function toFlowElements(
  graph?: BuildGraphResolveResponse | null,
  graphNodes: BuildGraphNode[] = graph?.nodes ?? [],
  graphEdges: BuildGraphEdge[] = graph?.edges ?? [],
  layoutVariant: GraphLayoutVariant = 'default'
): { nodes: Node[]; edges: Edge[] } {
  if (!graph) return { nodes: [], edges: [] };
  const focusNodeIds = new Set(graph.focusNodeIds);
  const nodeIdCounts = new Map<string, number>();
  const firstFlowNodeIdByGraphNodeId = new Map<string, string>();
  const placedNodeRects: Array<{ x: number; y: number; width: number; height: number }> = [];
  const previewPositions = layoutVariant === 'preview'
    ? previewLayoutPositions(graphNodes)
    : new Map<string, { x: number; y: number }>();
  const nodes = graphNodes.map((node, index) => {
    const graphNodeId = String(node.id);
    const currentCount = nodeIdCounts.get(graphNodeId) ?? 0;
    nodeIdCounts.set(graphNodeId, currentCount + 1);
    const flowNodeId = currentCount === 0 ? graphNodeId : `${graphNodeId}__${currentCount + 1}`;
    if (!firstFlowNodeIdByGraphNodeId.has(graphNodeId)) {
      firstFlowNodeIdByGraphNodeId.set(graphNodeId, flowNodeId);
    }
    const category = String(node.category ?? node.id).toUpperCase();
    const isPriceNode = isPriceGraphNode(node);
    const basePosition = previewPositions.get(graphNodeId) ?? graphNodePosition(node.position) ?? categoryPositions[category] ?? {
      x: 20 + (index % 3) * 300,
      y: 80 + Math.floor(index / 3) * 210
    };
    const size = nodeSize(node);
    const position = resolveNodePosition(basePosition, size, placedNodeRects);
    placedNodeRects.push({ ...position, ...size });
    return {
      id: flowNodeId,
      type: isPriceNode ? 'priceTotal' : 'graphCard',
      position,
      ...(isPriceNode ? {} : {
        sourcePosition: Position.Right,
        targetPosition: Position.Left
      }),
      data: {
        originalId: graphNodeId,
        label: nodeLabel(node),
        category: node.category,
        status: node.status
      },
      className: focusNodeIds.has(node.id) ? 'buildgraph-flow-node buildgraph-flow-node--focus' : 'buildgraph-flow-node',
      style: nodeStyle(node, size)
    } satisfies Node;
  });
  const edges = graphEdges.map((edge) => ({
    id: edge.id,
    source: firstFlowNodeIdByGraphNodeId.get(String(edge.source)) ?? edge.source,
    target: firstFlowNodeIdByGraphNodeId.get(String(edge.target)) ?? edge.target,
    label: edge.label,
    // React Flow 내장 엣지 타입에 'bezier'는 없다. 'default'가 곧 bezier 곡선이다.
    // ('bezier' 지정 시 매 엣지마다 "Edge type bezier not found" 경고 후 default로 폴백)
    type: layoutVariant === 'preview' ? 'smoothstep' : 'default',
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

function previewLayoutPositions(
  graphNodes: BuildGraphNode[]
) {
  const positions = new Map<string, { x: number; y: number }>();
  let partIndex = 0;
  let priceIndex = 0;

  for (const node of graphNodes) {
    const category = String(node.category ?? node.id).toUpperCase();
    if (isPriceGraphNode(node)) {
      positions.set(String(node.id), previewCategoryPositions.PRICE ?? previewPricePositions[priceIndex] ?? fallbackPreviewPricePosition(priceIndex));
      priceIndex += 1;
      continue;
    }
    positions.set(String(node.id), previewCategoryPositions[category] ?? previewNodePositions[partIndex] ?? fallbackPreviewPosition(partIndex));
    partIndex += 1;
  }

  return positions;
}

function fallbackPreviewPosition(index: number) {
  const adjustedIndex = Math.max(0, index - previewNodePositions.length);
  return {
    x: 55 + (adjustedIndex % 3) * 285,
    y: 780 + Math.floor(adjustedIndex / 3) * 180
  };
}

function fallbackPreviewPricePosition(index: number) {
  const adjustedIndex = Math.max(0, index - previewPricePositions.length);
  return {
    x: 340,
    y: 780 + adjustedIndex * 120
  };
}

function graphNodePosition(position: BuildGraphNode['position']) {
  if (!position || !Number.isFinite(position.x) || !Number.isFinite(position.y)) {
    return null;
  }
  // `/self-quote` 슬롯 보드 배치는 같은 API를 통해 0~100 퍼센트 좌표로 저장될 수 있다.
  // 일반 관계도는 픽셀 좌표계를 쓰므로 이 값은 무시하고 기존 category 기본 좌표를 유지한다.
  if (position.x <= 100 && position.y <= 100) {
    return null;
  }
  return {
    x: Math.max(0, position.x),
    y: Math.max(0, position.y)
  };
}

function withDisplayTotalPrice(
  graph: BuildGraphResolveResponse | null | undefined,
  totalPrice?: number
) {
  if (!graph || typeof totalPrice !== 'number') return graph;
  let changed = false;
  const nodes = graph.nodes.map((node) => {
    if (!isPriceGraphNode(node) || typeof node.price === 'number') return node;
    changed = true;
    return { ...node, price: totalPrice };
  });
  return changed ? { ...graph, nodes } : graph;
}

function nodeLabel(node: BuildGraphResolveResponse['nodes'][number]) {
  const priceLabel = nodePriceLabel(node);
  return (
    <div className="buildgraph-node-card buildgraph-node-card-main" title={node.label}>
      <div className="buildgraph-node-card-main-header">
        <div className="buildgraph-node-category-label">{nodeCategoryLabel(node)}</div>
        <div className={`buildgraph-node-status-label ${statusBadgeTone(node.status)}`}>{statusLabel(node.status)}</div>
      </div>
      <div className="buildgraph-node-main-label">{node.label}</div>
      {priceLabel ? <div className="buildgraph-node-price-label">{priceLabel}</div> : null}
    </div>
  );
}

function nodePriceLabel(node: BuildGraphResolveResponse['nodes'][number]) {
  if (!isPriceGraphNode(node) || typeof node.price !== 'number') return null;
  return `${node.price.toLocaleString()}원`;
}

function isPriceGraphNode(node: Pick<BuildGraphNode, 'category' | 'type' | 'id'>) {
  return String(node.category ?? node.id).toUpperCase() === 'PRICE';
}

function nodeCategoryLabel(node: BuildGraphResolveResponse['nodes'][number]) {
  const category = typeof node.category === 'string' ? node.category.toUpperCase() : '';
  if (isPartCategory(category)) return PART_CATEGORY_LABELS[category];
  if (category === 'PRICE') return '총액';
  if (node.type === 'CONSTRAINT') return '조건';
  return typeof node.category === 'string' && node.category.trim() ? node.category : node.type;
}

function nodeStyle(node: BuildGraphResolveResponse['nodes'][number], size = nodeSize(node)) {
  const status = node.status;
  const base = {
    borderRadius: 10,
    borderWidth: status === 'PASS' ? 1 : 2,
    borderStyle: 'solid',
    padding: 0,
    width: size.width,
    height: size.height,
    minWidth: size.width,
    minHeight: size.height,
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

function nodeSize(node: BuildGraphResolveResponse['nodes'][number]) {
  const category = String(node.category ?? node.id).toUpperCase();
  if (category === 'PRICE') return PRICE_NODE_SIZE;
  if (category === 'MOTHERBOARD' || category === 'CASE') return WIDE_NODE_SIZE;
  if (String(node.label).length >= 16) return WIDE_NODE_SIZE;
  return DEFAULT_NODE_SIZE;
}

function resolveNodePosition(
  basePosition: { x: number; y: number },
  size: { width: number; height: number },
  placedNodeRects: Array<{ x: number; y: number; width: number; height: number }>
) {
  for (const offset of NODE_POSITION_OFFSETS) {
    const candidate = {
      x: Math.max(0, basePosition.x + offset.x),
      y: Math.max(0, basePosition.y + offset.y)
    };
    if (!placedNodeRects.some((rect) => nodeRectsOverlap(candidate, size, rect))) {
      return candidate;
    }
  }

  const fallbackIndex = placedNodeRects.length + 1;
  return {
    x: basePosition.x + 300 * (fallbackIndex % 4),
    y: basePosition.y + 150 * Math.floor(fallbackIndex / 4)
  };
}

function nodeRectsOverlap(
  position: { x: number; y: number },
  size: { width: number; height: number },
  rect: { x: number; y: number; width: number; height: number }
) {
  return (
    position.x < rect.x + rect.width + NODE_COLLISION_GAP
    && position.x + size.width + NODE_COLLISION_GAP > rect.x
    && position.y < rect.y + rect.height + NODE_COLLISION_GAP
    && position.y + size.height + NODE_COLLISION_GAP > rect.y
  );
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
  rejectedCount,
  onClose,
  onViewCategoryList
}: {
  variant?: 'inline' | 'floating';
  activeNode: BuildGraphNode;
  activeNodeCategory: PartCategory | null;
  candidateContext?: CandidateContext;
  candidates: CompatiblePartCandidate[];
  isLoading: boolean;
  isError: boolean;
  rejectedCount: number;
  onClose?: () => void;
  onViewCategoryList?: () => void;
}) {
  const panelClassName = variant === 'floating'
    ? 'rounded-xl border border-commerce-line bg-white p-4 shadow-2xl'
    : 'border-t border-commerce-line bg-white p-4 shadow-[0_-10px_30px_rgba(15,23,42,0.06)] lg:absolute lg:right-4 lg:top-4 lg:z-10 lg:max-h-[calc(100%-2rem)] lg:w-[330px] lg:overflow-y-auto lg:rounded-xl lg:border lg:shadow-2xl';

  return (
    <div
      data-testid="graph-node-candidate-panel"
      className={panelClassName}
    >
      {onClose ? (
        <div className="mb-3 flex justify-end">
          <button
            type="button"
            aria-label="선택한 부품 상세 닫기"
            onClick={onClose}
            className="grid h-8 w-8 place-items-center rounded-md border border-commerce-line bg-white text-slate-600 transition hover:border-commerce-sale hover:text-commerce-sale focus:outline-none focus:ring-2 focus:ring-brand-blue"
          >
            <X size={16} />
          </button>
        </div>
      ) : null}
      <SelectedNodePanel node={activeNode} />
      {activeNodeCategory && onViewCategoryList ? (
        <button
          type="button"
          onClick={onViewCategoryList}
          className="mb-4 w-full rounded-md border border-commerce-line bg-white px-3 py-2 text-xs font-black text-slate-700 transition hover:border-commerce-ink hover:text-commerce-ink focus:outline-none focus:ring-2 focus:ring-brand-blue"
        >
          이 카테고리 목록 보기
        </button>
      ) : null}
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
          <div className="mt-1 text-[11px] font-bold text-slate-500">서버 검증 기준</div>
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
                    <span className="text-[10px] font-black uppercase tracking-wide text-slate-400">{candidate.checkedTools.map((tool) => TOOL_LABELS[tool] ?? tool).join(' · ') || '검증 항목 없음'}</span>
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

function CompositeScoreMeter({ score }: { score: NonNullable<BuildGraphResolveResponse['compositeScore']> }) {
  const percent = Math.max(0, Math.min(100, Math.round((score.score / Math.max(1, score.maxScore)) * 100)));
  const topComponents = [...score.components]
    .sort((left, right) => right.maxScore - left.maxScore)
    .slice(0, 3);
  return (
    <div data-testid="build-composite-score" className="rounded-md border border-commerce-line bg-slate-50 p-3 text-left shadow-sm sm:min-w-[260px]">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="text-[11px] font-black uppercase text-slate-400">종합 점수</div>
          <div className={`mt-0.5 text-2xl font-black ${scoreTone(score.score)}`}>
            {score.score.toLocaleString('ko-KR')}<span className="text-sm text-slate-400"> / {score.maxScore.toLocaleString('ko-KR')}</span>
          </div>
        </div>
        <div className="rounded bg-white px-2 py-1 text-right text-[11px] font-black text-commerce-ink ring-1 ring-commerce-line">
          <div>{score.grade}</div>
          <div className="text-slate-500">{score.label}</div>
        </div>
      </div>
      <div className="mt-2 h-2 rounded-full bg-slate-200">
        <div className={`h-2 rounded-full ${score.score <= 0 ? 'bg-red-500' : 'bg-brand-blue'}`} style={{ width: `${percent}%` }} />
      </div>
      <div className="mt-2 grid gap-1">
        {topComponents.map((component) => (
          <div key={component.key} className="flex items-center justify-between gap-3 text-[11px] font-bold text-slate-600">
            <span>{component.label}</span>
            <span className="text-commerce-ink">{component.score}/{component.maxScore}</span>
          </div>
        ))}
      </div>
      {score.requestFit ? (
        <div className={`mt-2 rounded px-2 py-1 text-[11px] font-black ${requestFitTone(score.requestFit.status)}`}>
          {requestFitLabel(score.requestFit)}
        </div>
      ) : null}
    </div>
  );
}

function requestFitLabel(requestFit: NonNullable<BuildGraphResolveResponse['compositeScore']>['requestFit']) {
  if (!requestFit) return '요청 예산 정보 없음';
  const formatter = new Intl.NumberFormat('ko-KR');
  if (requestFit.status === 'OVER_BUDGET') {
    return `요청 예산 초과 · 차액 ${formatter.format(Math.abs(requestFit.priceDiff ?? 0))}원`;
  }
  if (requestFit.status === 'PASS') return '요청 예산 적합';
  if (requestFit.status === 'WARN') return '요청 예산 근접';
  return requestFit.summary || '요청 예산 정보 없음';
}

function requestFitTone(status?: string) {
  if (status === 'OVER_BUDGET') return 'bg-red-50 text-red-700';
  if (status === 'WARN') return 'bg-amber-50 text-amber-700';
  if (status === 'PASS') return 'bg-emerald-50 text-emerald-700';
  return 'bg-slate-100 text-slate-500';
}

function scoreTone(score: number) {
  if (score >= 930) return 'text-brand-blue';
  if (score >= 850) return 'text-commerce-green';
  if (score >= 750) return 'text-commerce-ink';
  if (score >= 600) return 'text-amber-600';
  return 'text-red-600';
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
  return '호환 가능';
}

const TOOL_LABELS: Record<string, string> = {
  compatibility: '호환성',
  power: '전력',
  size: '규격',
  performance: '성능',
  price: '가격'
};

function isPartCategory(value: string): value is PartCategory {
  return Object.keys(PART_CATEGORY_LABELS).includes(value);
}
