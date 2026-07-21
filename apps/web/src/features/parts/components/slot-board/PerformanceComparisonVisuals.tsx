import { useEffect, useRef, useState } from 'react';

const COMPOSITE_ARC_PATH = 'M 24 112 A 86 86 0 0 1 196 112';
const SWEEP_DURATION_MS = 600;

export function prefersReducedMotion() {
  return typeof window === 'undefined' || window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function easeOutCubic(value: number) {
  return 1 - Math.pow(1 - value, 3);
}

export function useAnimatedNumber(target: number, initial?: number): number {
  const safeTarget = Number.isFinite(target) ? target : 0;
  const initialValue = typeof initial === 'number' && Number.isFinite(initial) ? initial : safeTarget;
  const [value, setValue] = useState(initialValue);
  const valueRef = useRef(initialValue);
  const frameRef = useRef<number | null>(null);
  const settleTimerRef = useRef<number | null>(null);

  useEffect(() => {
    if (safeTarget === valueRef.current) return;
    if (prefersReducedMotion()) {
      valueRef.current = safeTarget;
      setValue(safeTarget);
      return;
    }

    const clearSettle = () => {
      if (settleTimerRef.current !== null) window.clearTimeout(settleTimerRef.current);
      settleTimerRef.current = null;
    };
    const from = valueRef.current;
    const startedAt = performance.now();
    const tick = (now: number) => {
      const progress = Math.min(1, (now - startedAt) / SWEEP_DURATION_MS);
      const next = from + (safeTarget - from) * easeOutCubic(progress);
      valueRef.current = next;
      setValue(next);
      if (progress < 1) {
        frameRef.current = requestAnimationFrame(tick);
      } else {
        frameRef.current = null;
        clearSettle();
      }
    };

    frameRef.current = requestAnimationFrame(tick);
    settleTimerRef.current = window.setTimeout(() => {
      settleTimerRef.current = null;
      if (frameRef.current !== null) {
        cancelAnimationFrame(frameRef.current);
        frameRef.current = null;
      }
      valueRef.current = safeTarget;
      setValue(safeTarget);
    }, SWEEP_DURATION_MS + 100);

    return () => {
      if (frameRef.current !== null) cancelAnimationFrame(frameRef.current);
      frameRef.current = null;
      clearSettle();
    };
  }, [safeTarget]);

  return value;
}

export function CompositeGhostArc({
  baseScore,
  compareScore,
  maxScore,
  compareKey,
  tone = 'brand',
  testIdPrefix = 'quote-composite',
  className = 'mx-auto max-w-[280px]'
}: {
  baseScore: number;
  compareScore: number;
  maxScore: number;
  compareKey: string;
  tone?: 'brand' | 'danger';
  testIdPrefix?: string;
  className?: string;
}) {
  const safeMax = Math.max(1, maxScore);
  const displayCompare = useAnimatedNumber(compareScore, baseScore);
  const basePercent = Math.max(0, Math.min(100, (Math.max(0, baseScore) / safeMax) * 100));
  const comparePercent = Math.max(0, Math.min(100, (Math.max(0, displayCompare) / safeMax) * 100));
  const delta = Math.round(compareScore) - Math.round(baseScore);
  const changedStroke = tone === 'danger' ? 'stroke-red-500' : 'stroke-brand-blue';
  const changedText = tone === 'danger' ? 'text-red-600' : 'text-brand-blue';

  return (
    <div
      data-testid={`${testIdPrefix}-ghost-gauge`}
      className={`${className} text-center`}
      aria-label={`종합 점수 기존 ${Math.round(baseScore).toLocaleString('ko-KR')}점에서 변경 ${Math.round(compareScore).toLocaleString('ko-KR')}점`}
    >
      <div className="relative">
        <svg className="h-[150px] w-full overflow-visible" viewBox="0 0 220 132" role="img" aria-hidden="true">
          <path
            d={COMPOSITE_ARC_PATH}
            fill="none"
            className="stroke-slate-200"
            strokeWidth={18}
            strokeLinecap="butt"
            pathLength={100}
          />
          <path
            d={COMPOSITE_ARC_PATH}
            fill="none"
            className="stroke-slate-400 opacity-50"
            strokeWidth={18}
            strokeLinecap="butt"
            pathLength={100}
            strokeDasharray={`${basePercent} 100`}
          />
          <path
            d={COMPOSITE_ARC_PATH}
            fill="none"
            className={changedStroke}
            strokeWidth={10}
            strokeLinecap="butt"
            pathLength={100}
            strokeDasharray={`${comparePercent} 100`}
          />
        </svg>
        <div className="absolute inset-x-0 bottom-3 px-1">
          <div className="flex flex-wrap items-baseline justify-center gap-1 font-black leading-none">
            <span data-testid={`${testIdPrefix}-ghost-base`} className="text-lg text-slate-400">
              {Math.round(baseScore).toLocaleString('ko-KR')}
            </span>
            <span aria-hidden="true" className="text-sm text-slate-400">→</span>
            <span data-testid={`${testIdPrefix}-compare-score`} className={`text-3xl ${changedText}`}>
              {Math.round(displayCompare).toLocaleString('ko-KR')}
            </span>
          </div>
          <div className="mt-1.5 flex justify-center">
            <span
              key={compareKey}
              data-testid={`${testIdPrefix}-compare-delta`}
              className={`perf-pop-in rounded-full border px-1.5 py-0.5 text-[10px] font-black ${comparisonDeltaTone(delta)}`}
            >
              {delta > 0 ? '+' : ''}{delta}점
            </span>
          </div>
        </div>
      </div>
      <div className="-mt-3 flex items-center justify-between px-4 text-[10px] font-bold text-slate-400" aria-hidden="true">
        <span>0</span>
        <span>{safeMax.toLocaleString('ko-KR')}</span>
      </div>
    </div>
  );
}

function comparisonDeltaTone(delta: number) {
  if (delta > 0) return 'border-emerald-200 bg-emerald-50 text-emerald-700';
  if (delta < 0) return 'border-red-200 bg-red-50 text-red-700';
  return 'border-slate-200 bg-slate-50 text-slate-500';
}
