import {
  Component,
  Suspense,
  lazy,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ErrorInfo,
  type ReactNode
} from 'react';
import { Box, Focus, RotateCcw, Unplug, Wrench } from 'lucide-react';
import type { PartCategory } from '../../../quote/aiSelection';
import type { QuoteDraftItem } from '../../types';
import { RECOMMENDED_SLOT_ORDER, slotConfigFor } from './slotBoardConfig';
import {
  OPENDB_COMMON_PROFILE,
  resolveAssemblyOccluders
} from './assembly3dProfile';

const LazyThreeDAssemblyScene = lazy(() =>
  import('./ThreeDAssemblyScene').then((module) => ({ default: module.ThreeDAssemblyScene }))
);

const DISASSEMBLY_ORDER: PartCategory[] = [
  'CASE',
  'PSU',
  'GPU',
  'COOLER',
  'STORAGE',
  'RAM',
  'CPU',
  'MOTHERBOARD'
];

const STATUS_COPY = {
  PASS: { label: '호환 가능', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  WARN: { label: '간섭 주의', className: 'border-amber-200 bg-amber-50 text-amber-700' },
  FAIL: { label: '장착 불가', className: 'border-red-200 bg-red-50 text-red-700' }
} as const;

type AssemblyStatus = keyof typeof STATUS_COPY;

type ThreeDAssemblyViewProps = {
  items: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  aiFocusCategories: PartCategory[];
  statusByCategory: ReadonlyMap<string, AssemblyStatus>;
  onSelectCategory: (category: PartCategory) => void;
  onClearSelection?: () => void;
  onClearAiFocus?: () => void;
  onProblemOpen: (category: PartCategory) => void;
  hasProblem: (category: PartCategory) => boolean;
  fallback: ReactNode;
};

export function ThreeDAssemblyView({
  items,
  selectedCategory,
  aiFocusCategories,
  statusByCategory,
  onSelectCategory,
  onClearSelection,
  onClearAiFocus,
  onProblemOpen,
  hasProblem,
  fallback
}: ThreeDAssemblyViewProps) {
  const reducedMotion = useReducedMotion();
  const webglAvailable = useMemo(supportsWebGL2, []);
  const renderedCategories = useMemo(
    () => RECOMMENDED_SLOT_ORDER.filter((category) => items.some((item) => item.category === category)),
    [items]
  );
  const renderedCategorySet = useMemo(() => new Set(renderedCategories), [renderedCategories]);
  const [detachedCategories, setDetachedCategories] = useState<Set<PartCategory>>(() => new Set());
  const [animationState, setAnimationState] = useState<'idle' | 'running'>('idle');
  const [cameraResetKey, setCameraResetKey] = useState(0);
  const [announcement, setAnnouncement] = useState('');
  const sequenceTokenRef = useRef(0);
  const counts = useMemo(() => assemblyVisualCounts(items), [items]);
  const selectedItems = selectedCategory
    ? items.filter((item) => item.category === selectedCategory)
    : [];
  const selectedItem = selectedItems[0];
  const selectedStatus = selectedCategory
    ? statusByCategory.get(selectedCategory) ?? 'PASS'
    : 'PASS';
  const selectedLabel = selectedCategory ? slotConfigFor(selectedCategory)?.label ?? selectedCategory : '';
  const anyDetached = detachedCategories.size > 0;
  const allDetached = renderedCategories.length > 0 && renderedCategories.every((category) => detachedCategories.has(category));
  const occludingCategories = resolveAssemblyOccluders(
    selectedCategory,
    aiFocusCategories,
    detachedCategories
  );

  useEffect(() => {
    setDetachedCategories((current) => {
      const next = new Set([...current].filter((category) => renderedCategorySet.has(category)));
      return setsEqual(current, next) ? current : next;
    });
  }, [renderedCategorySet]);

  useEffect(() => () => {
    sequenceTokenRef.current += 1;
  }, []);

  const toggleSelectedPart = () => {
    if (!selectedCategory || !renderedCategorySet.has(selectedCategory) || animationState === 'running') return;
    const willDetach = !detachedCategories.has(selectedCategory);
    setDetachedCategories((current) => {
      const next = new Set(current);
      if (willDetach) next.add(selectedCategory);
      else next.delete(selectedCategory);
      return next;
    });
    setAnnouncement(`${selectedLabel}${willDetach ? '를 분리했습니다' : '를 다시 장착했습니다'}.`);
  };

  const runSequence = useCallback(async (detach: boolean) => {
    if (animationState === 'running' || renderedCategories.length === 0) return;
    const token = sequenceTokenRef.current + 1;
    sequenceTokenRef.current = token;
    setAnimationState('running');
    const order = (detach ? DISASSEMBLY_ORDER : [...DISASSEMBLY_ORDER].reverse())
      .filter((category) => renderedCategorySet.has(category));

    if (reducedMotion) {
      setDetachedCategories(detach ? new Set(renderedCategories) : new Set());
    } else {
      for (const category of order) {
        if (sequenceTokenRef.current !== token) return;
        setDetachedCategories((current) => {
          const next = new Set(current);
          if (detach) next.add(category);
          else next.delete(category);
          return next;
        });
        await wait(90);
      }
      await wait(320);
    }

    if (sequenceTokenRef.current === token) {
      setAnimationState('idle');
      setAnnouncement(detach ? '선택한 부품을 전체 분해했습니다.' : '선택한 부품을 전체 조립했습니다.');
    }
  }, [animationState, reducedMotion, renderedCategories, renderedCategorySet]);

  if (!webglAvailable) {
    return fallback;
  }

  const detachedManifest = renderedCategories.filter((category) => detachedCategories.has(category)).join(',');
  const assemblyMode = allDetached ? 'detached' : anyDetached ? 'partial' : 'assembled';

  return (
    <SceneErrorBoundary fallback={fallback}>
      <div
        data-testid="slot-board"
        data-visual-mode="isometric"
        data-ai-focus-active={aiFocusCategories.length > 0 ? 'true' : 'false'}
        onClick={(event) => {
          if (event.target === event.currentTarget && selectedCategory) onClearSelection?.();
        }}
        className="relative flex min-h-0 flex-1 flex-col overflow-hidden bg-[#eef2f6]"
      >
        <div
          data-testid="assembly-3d-view"
          data-layout-profile={OPENDB_COMMON_PROFILE.id}
          data-rendered-categories={renderedCategories.join(',')}
          data-detached-categories={detachedManifest}
          data-assembly-mode={assemblyMode}
          data-animation-state={animationState}
          data-case-panel={detachedCategories.has('CASE') ? 'open' : 'closed'}
          data-ram-instances={counts.RAM.visible}
          data-storage-instances={counts.STORAGE.visible}
          data-reduced-motion={reducedMotion ? 'true' : 'false'}
          data-selected-category={selectedItem && selectedCategory ? selectedCategory : ''}
          data-ai-focus-categories={RECOMMENDED_SLOT_ORDER.filter((category) => aiFocusCategories.includes(category)).join(',')}
          data-ai-dimmed-categories={aiFocusCategories.length > 0
            ? renderedCategories.filter((category) => !aiFocusCategories.includes(category)).join(',')
            : ''}
          data-occluding-categories={occludingCategories.join(',')}
          className="relative min-h-0 flex-1 overflow-hidden"
          onPointerDown={() => {
            if (aiFocusCategories.length > 0) onClearAiFocus?.();
          }}
        >
          {renderedCategories.length > 0 ? (
            <Suspense fallback={<AssemblyLoadingState reducedMotion={reducedMotion} />}>
              <LazyThreeDAssemblyScene
                items={items}
                selectedCategory={selectedCategory}
                detachedCategories={detachedCategories}
                aiFocusCategories={aiFocusCategories}
                statusByCategory={statusByCategory}
                reducedMotion={reducedMotion}
                cameraResetKey={cameraResetKey}
                onSelectCategory={onSelectCategory}
              />
            </Suspense>
          ) : (
            <div className="absolute inset-0 grid place-items-center bg-[#eef2f6] px-6 text-center">
              <div>
                <Box className="mx-auto h-9 w-9 text-slate-400" aria-hidden="true" />
                <p className="mt-3 text-sm font-black text-slate-800">3D로 표시할 부품을 견적에 추가하세요</p>
                <p className="mt-1 text-xs font-semibold text-slate-500">왼쪽 체크리스트에서 부품을 선택하면 조립 위치에 표시됩니다.</p>
              </div>
            </div>
          )}

          {renderedCategories.length > 0 ? (
            <div className="pointer-events-none absolute left-3 right-3 top-3 z-10 flex items-start justify-between gap-3">
              <div
                id="assembly-3d-help"
                className="rounded-md border border-slate-200 bg-white/90 px-2.5 py-2 text-[11px] font-bold text-slate-600 shadow-sm backdrop-blur-sm"
              >
                드래그로 회전 · 휠로 확대
              </div>
              <div className="pointer-events-auto flex items-center gap-1.5">
                <button
                  type="button"
                  aria-label="3D 시점 초기화"
                  onClick={() => setCameraResetKey((key) => key + 1)}
                  className="inline-flex h-9 items-center gap-1.5 rounded-md border border-slate-300 bg-white px-2.5 text-[11px] font-black text-slate-700 shadow-sm transition hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-300"
                >
                  <RotateCcw size={13} aria-hidden="true" />
                  시점 초기화
                </button>
                <button
                  type="button"
                  aria-label={anyDetached ? '전체 조립' : '전체 분해'}
                  disabled={animationState === 'running'}
                  onClick={() => void runSequence(!anyDetached)}
                  className="inline-flex h-9 items-center gap-1.5 rounded-md bg-brand-blue px-3 text-[11px] font-black text-white shadow-sm transition hover:bg-blue-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-300 disabled:cursor-wait disabled:bg-slate-400"
                >
                  {anyDetached ? <Wrench size={13} aria-hidden="true" /> : <Unplug size={13} aria-hidden="true" />}
                  {anyDetached ? '전체 조립' : '전체 분해'}
                </button>
              </div>
            </div>
          ) : null}
        </div>

        {renderedCategories.length > 0 ? (
          <div className="border-t border-slate-200 bg-white px-3 py-3 text-slate-900 shadow-[0_-8px_24px_rgba(15,23,42,0.04)]">
            {selectedItem && selectedCategory ? (
              <div data-testid="assembly-selected-part" className="flex flex-wrap items-center gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-xs font-black">{selectedLabel}</span>
                    <span className={`rounded-full border px-2 py-0.5 text-[10px] font-black ${STATUS_COPY[selectedStatus].className}`}>
                      {STATUS_COPY[selectedStatus].label}
                    </span>
                  </div>
                  <p className="mt-1 truncate text-sm font-black text-slate-900">{selectedItem.name}</p>
                  {selectedCategory === 'RAM' || selectedCategory === 'STORAGE' ? (
                    <p className="mt-1 text-[11px] font-semibold text-slate-500">{quantitySummary(selectedCategory, counts)}</p>
                  ) : null}
                </div>
                {hasProblem(selectedCategory) ? (
                  <button
                    type="button"
                    onClick={() => onProblemOpen(selectedCategory)}
                    className="h-9 rounded-md border border-amber-300 bg-amber-50 px-3 text-[11px] font-black text-amber-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber-300"
                  >
                    문제 사유 보기
                  </button>
                ) : null}
                <button
                  type="button"
                  aria-label={`${selectedLabel} ${detachedCategories.has(selectedCategory) ? '다시 장착' : '분리'}`}
                  disabled={animationState === 'running'}
                  onClick={toggleSelectedPart}
                  className="h-9 rounded-md bg-brand-blue px-3 text-[11px] font-black text-white transition hover:bg-blue-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-300 disabled:cursor-wait disabled:bg-slate-400"
                >
                  {detachedCategories.has(selectedCategory) ? '다시 장착' : '분리'}
                </button>
              </div>
            ) : (
              <div className="flex items-center gap-2 text-xs font-bold text-slate-500">
                <Focus size={14} aria-hidden="true" />
                3D 부품을 클릭하거나 왼쪽 체크리스트에서 부품을 선택하세요.
              </div>
            )}
            <p className="mt-2 border-t border-slate-100 pt-2 text-[10px] font-semibold text-slate-500">
              대표 규격:{' '}
              <a
                href={OPENDB_COMMON_PROFILE.source.url}
                target="_blank"
                rel="noreferrer"
                className="font-black text-slate-700 underline decoration-slate-300 underline-offset-2 hover:text-brand-blue"
              >
                BuildCores OpenDB
              </a>
              {' · '}
              <a
                href={OPENDB_COMMON_PROFILE.source.licenseUrl}
                target="_blank"
                rel="noreferrer"
                className="font-black text-slate-700 underline decoration-slate-300 underline-offset-2 hover:text-brand-blue"
              >
                ODC-By 1.0
              </a>
            </p>
          </div>
        ) : null}
        <div data-testid="assembly-announcement" role="status" aria-live="polite" className="sr-only">{announcement}</div>
      </div>
    </SceneErrorBoundary>
  );
}

function AssemblyLoadingState({ reducedMotion }: { reducedMotion: boolean }) {
  return (
    <div data-testid="assembly-3d-loading" className="absolute inset-0 grid place-items-center bg-[#eef2f6]">
      <div className="w-48 space-y-2" aria-label="3D 조립 화면을 불러오는 중">
        <div className={`h-2 rounded bg-slate-300 ${reducedMotion ? '' : 'animate-pulse'}`} />
        <div className={`mx-auto h-2 w-32 rounded bg-slate-200 ${reducedMotion ? '' : 'animate-pulse'}`} />
      </div>
    </div>
  );
}

function useReducedMotion() {
  const [reduced, setReduced] = useState(() =>
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
      : false
  );

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined;
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    const sync = () => setReduced(query.matches);
    sync();
    query.addEventListener('change', sync);
    return () => query.removeEventListener('change', sync);
  }, []);

  return reduced;
}

type VisualCount = { total: number; visible: number; overflow: number };
type VisualCounts = { RAM: VisualCount; STORAGE: VisualCount };

function assemblyVisualCounts(items: QuoteDraftItem[]): VisualCounts {
  const count = (category: PartCategory) => items
    .filter((item) => item.category === category)
    .reduce((sum, item) => sum + Math.max(1, item.quantity), 0);
  const ramTotal = count('RAM');
  const storageTotal = count('STORAGE');
  return {
    RAM: { total: ramTotal, visible: Math.min(ramTotal, 4), overflow: Math.max(0, ramTotal - 4) },
    STORAGE: { total: storageTotal, visible: Math.min(storageTotal, 2), overflow: Math.max(0, storageTotal - 2) }
  };
}

function quantitySummary(category: 'RAM' | 'STORAGE', counts: VisualCounts) {
  const count = counts[category];
  return count.overflow > 0
    ? `${count.visible}개 표시 · +${count.overflow}개 추가 구성`
    : `${count.visible}개 표시`;
}

function supportsWebGL2() {
  if (typeof document === 'undefined') return false;
  try {
    const canvas = document.createElement('canvas');
    return Boolean(canvas.getContext('webgl2'));
  } catch {
    return false;
  }
}

function setsEqual<T>(left: ReadonlySet<T>, right: ReadonlySet<T>) {
  return left.size === right.size && [...left].every((value) => right.has(value));
}

function wait(duration: number) {
  return new Promise<void>((resolve) => window.setTimeout(resolve, duration));
}

class SceneErrorBoundary extends Component<{ fallback: ReactNode; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('3D assembly scene failed', error, info);
  }

  render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}
