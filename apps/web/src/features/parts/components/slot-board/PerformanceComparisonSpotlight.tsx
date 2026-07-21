import { createPortal } from 'react-dom';
import { useEffect, useRef } from 'react';
import { AlertTriangle, Sparkles, X } from 'lucide-react';
import { CompositeGhostArc } from './PerformanceComparisonVisuals';

export type PerformanceComparisonSpotlightProps = {
  open: boolean;
  requestKey: string;
  categoryLabel: string;
  currentPartName: string;
  targetPartName: string;
  baseScore: number;
  compareScore: number;
  maxScore: number;
  beforeTotalPrice?: number;
  afterTotalPrice?: number;
  gameLabel: string;
  resolutionLabel: string;
  baseFps?: number;
  compareFps?: number;
  fpsComparable: boolean;
  fpsLoading: boolean;
  onDismiss: () => void;
};

export function PerformanceComparisonSpotlight(props: PerformanceComparisonSpotlightProps) {
  const dialogRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!props.open) return;
    const previousActiveElement = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    dialogRef.current?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      props.onDismiss();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
      previousActiveElement?.focus();
    };
  }, [props.open, props.onDismiss]);

  if (!props.open || typeof document === 'undefined') return null;

  const scoreDelta = Math.round(props.compareScore) - Math.round(props.baseScore);
  const needsReview = props.compareScore <= 0 || scoreDelta <= 0;
  const title = props.compareScore <= 0
    ? '변경안 확인 필요'
    : scoreDelta > 0
      ? '성능 개선 예상'
      : '변경안 성능 비교';
  const titleId = `performance-comparison-title-${sanitizeDomId(props.requestKey)}`;
  const hasPrice = isFinitePositive(props.beforeTotalPrice) && isFinitePositive(props.afterTotalPrice);
  const hasFps = isFinitePositive(props.baseFps) && isFinitePositive(props.compareFps);
  const fpsDelta = hasFps && props.fpsComparable
    ? Math.round((((props.compareFps as number) - (props.baseFps as number)) / (props.baseFps as number)) * 100)
    : null;

  return createPortal(
    <div
      data-testid="performance-comparison-spotlight-backdrop"
      className="fixed inset-0 z-[140] flex items-center justify-center bg-slate-950/55 p-4 backdrop-blur-[1px]"
      onClick={props.onDismiss}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        data-testid="performance-comparison-spotlight"
        className={`relative max-h-[calc(100dvh-32px)] w-[min(640px,calc(100vw-32px))] overflow-y-auto rounded-lg border bg-white p-4 shadow-2xl outline-none sm:p-6 ${
          needsReview ? 'border-red-200' : 'border-blue-200'
        }`}
      >
        <button
          type="button"
          aria-label="성능 비교 닫기"
          onClick={props.onDismiss}
          className="absolute right-3 top-3 z-10 inline-flex h-8 w-8 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-500 shadow-sm transition hover:border-slate-400 hover:text-slate-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue"
        >
          <X size={17} aria-hidden="true" />
        </button>

        <header className="pr-10 text-center">
          <div className={`mx-auto mb-2 inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-black ${
            needsReview ? 'bg-red-50 text-red-700' : 'bg-blue-50 text-brand-blue'
          }`}>
            {needsReview ? <AlertTriangle size={14} aria-hidden="true" /> : <Sparkles size={14} aria-hidden="true" />}
            AI 변경안 비교
          </div>
          <h2 id={titleId} className={`text-xl font-black sm:text-2xl ${needsReview ? 'text-red-700' : 'text-commerce-ink'}`}>
            {title}
          </h2>
          <p className="mt-1 text-xs font-bold text-slate-500 sm:text-sm">
            {props.categoryLabel} · {compactPartName(props.currentPartName)} → {compactPartName(props.targetPartName)}
          </p>
        </header>

        <div className="mx-auto mt-2 max-w-[340px]">
          <CompositeGhostArc
            baseScore={props.baseScore}
            compareScore={props.compareScore}
            maxScore={props.maxScore}
            compareKey={props.requestKey}
            tone={needsReview ? 'danger' : 'brand'}
            testIdPrefix="spotlight-composite"
            className="mx-auto max-w-[320px]"
          />
        </div>

        <div className={`mt-3 grid gap-3 ${hasPrice && (hasFps || props.fpsLoading) ? 'sm:grid-cols-2' : ''}`}>
          {hasPrice ? (
            <ComparisonMetricCard
              testId="spotlight-price-comparison"
              title="견적 예상가"
              before={`${formatWon(props.beforeTotalPrice as number)}원`}
              after={`${formatWon(props.afterTotalPrice as number)}원`}
              delta={formatWonDelta((props.afterTotalPrice as number) - (props.beforeTotalPrice as number))}
              beforeValue={props.beforeTotalPrice as number}
              afterValue={props.afterTotalPrice as number}
              tone="price"
            />
          ) : null}

          {hasFps ? (
            <ComparisonMetricCard
              testId="spotlight-fps-comparison"
              title={`${props.gameLabel} · ${props.resolutionLabel}`}
              before={`${Math.round(props.baseFps as number)} FPS`}
              after={`${Math.round(props.compareFps as number)} FPS`}
              delta={fpsDelta === null ? undefined : `${fpsDelta > 0 ? '+' : ''}${fpsDelta}%`}
              beforeValue={props.baseFps as number}
              afterValue={props.compareFps as number}
              tone={Number(props.compareFps) >= Number(props.baseFps) ? 'performance' : 'danger'}
            />
          ) : props.fpsLoading ? (
            <div data-testid="spotlight-fps-loading" className="rounded-lg border border-slate-200 bg-slate-50 p-3">
              <div className="text-xs font-black text-slate-600">{props.gameLabel} · {props.resolutionLabel}</div>
              <div className="mt-3 h-2 animate-pulse rounded-full bg-slate-200" />
              <div className="mt-2 text-[11px] font-bold text-slate-400">게임 성능을 계산하고 있습니다.</div>
            </div>
          ) : null}
        </div>

        {hasFps && !props.fpsComparable ? (
          <p data-testid="spotlight-fps-mismatch" className="mt-3 rounded-md bg-slate-50 px-3 py-2 text-[11px] font-bold text-slate-500">
            측정 조건이 달라 FPS 증감률은 표시하지 않고 각 참고값만 보여드립니다.
          </p>
        ) : null}
        {props.compareScore <= 0 ? (
          <p data-testid="spotlight-score-warning" className="mt-3 rounded-md border border-red-100 bg-red-50 px-3 py-2 text-xs font-bold text-red-700">
            변경 조합에 호환성 또는 장착 문제가 있습니다. 적용 전 상단 경고를 확인해 주세요.
          </p>
        ) : null}
        <p className="mt-4 text-center text-[11px] font-bold text-slate-400">아무 곳이나 누르면 비교 강조가 닫힙니다.</p>
      </div>
    </div>,
    document.body
  );
}

function ComparisonMetricCard({
  testId,
  title,
  before,
  after,
  delta,
  beforeValue,
  afterValue,
  tone
}: {
  testId: string;
  title: string;
  before: string;
  after: string;
  delta?: string;
  beforeValue: number;
  afterValue: number;
  tone: 'price' | 'performance' | 'danger';
}) {
  const max = Math.max(1, beforeValue, afterValue);
  const beforeWidth = Math.max(4, Math.min(100, (beforeValue / max) * 100));
  const afterWidth = Math.max(4, Math.min(100, (afterValue / max) * 100));
  const changedBar = tone === 'danger' ? 'bg-red-500' : tone === 'price' ? 'bg-[#de6c2d]' : 'bg-brand-blue';
  const changedText = tone === 'danger' ? 'text-red-600' : tone === 'price' ? 'text-[#de6c2d]' : 'text-brand-blue';

  return (
    <section data-testid={testId} className="rounded-lg border border-slate-200 bg-slate-50/80 p-3">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-xs font-black text-slate-600">{title}</h3>
        {delta ? <span className={`perf-pop-in text-xs font-black ${changedText}`}>{delta}</span> : null}
      </div>
      <div className="mt-3 space-y-2">
        <MetricBar label="기존" value={before} width={beforeWidth} barClass="bg-slate-400" />
        <MetricBar label="변경" value={after} width={afterWidth} barClass={changedBar} emphasized />
      </div>
    </section>
  );
}

function MetricBar({ label, value, width, barClass, emphasized = false }: {
  label: string;
  value: string;
  width: number;
  barClass: string;
  emphasized?: boolean;
}) {
  return (
    <div className="grid grid-cols-[30px_minmax(0,1fr)_auto] items-center gap-2 text-[11px] font-bold">
      <span className={emphasized ? 'font-black text-commerce-ink' : 'text-slate-500'}>{label}</span>
      <div className="h-2 overflow-hidden rounded-full bg-slate-200">
        <div className={`perf-bar-grow h-full rounded-full ${barClass}${emphasized ? ' perf-bar-stagger' : ''}`} style={{ width: `${width}%` }} />
      </div>
      <span className={emphasized ? 'font-black text-commerce-ink' : 'text-slate-500'}>{value}</span>
    </div>
  );
}

function isFinitePositive(value: number | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

function formatWon(value: number) {
  return Math.round(value).toLocaleString('ko-KR');
}

function formatWonDelta(value: number) {
  if (value === 0) return '변화 없음';
  return `${value > 0 ? '+' : '-'}${formatWon(Math.abs(value))}원`;
}

function compactPartName(value: string) {
  const normalized = value.trim().replace(/\s+/g, ' ');
  return normalized.length > 32 ? `${normalized.slice(0, 31)}…` : normalized;
}

function sanitizeDomId(value: string) {
  return value.replace(/[^a-zA-Z0-9_-]/g, '-');
}
