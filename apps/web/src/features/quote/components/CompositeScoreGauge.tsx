import { useEffect, useRef, useState } from 'react';
import type { BuildCompositeScore } from '../aiSelection';

type CompositeScoreGaugeSize = 'large' | 'medium' | 'mini';

type CompositeScoreGaugeProps = {
  score: BuildCompositeScore;
  size?: CompositeScoreGaugeSize;
  highlight?: boolean;
  className?: string;
  scoreTestId?: string;
  gaugeTestId?: string;
};

const GAUGE_ANIMATION_MS = 700;

const SIZE_STYLES: Record<CompositeScoreGaugeSize, {
  shell: string;
  svg: string;
  strokeWidth: number;
  center: string;
  scoreText: string;
  maxText: string;
  labelText: string;
  endpointText: string;
  showSummary: boolean;
  showEndpoints: boolean;
}> = {
  large: {
    shell: 'max-w-[280px]',
    svg: 'h-[150px]',
    strokeWidth: 18,
    center: 'bottom-4',
    scoreText: 'text-4xl',
    maxText: 'text-sm',
    labelText: 'text-xs',
    endpointText: 'text-[10px]',
    showSummary: true,
    showEndpoints: true
  },
  medium: {
    shell: 'max-w-[220px]',
    svg: 'h-[118px]',
    strokeWidth: 14,
    center: 'bottom-3',
    scoreText: 'text-2xl',
    maxText: 'text-xs',
    labelText: 'text-[11px]',
    endpointText: 'text-[10px]',
    showSummary: false,
    showEndpoints: true
  },
  mini: {
    shell: 'w-[96px]',
    svg: 'h-[58px]',
    strokeWidth: 10,
    center: 'bottom-0.5',
    scoreText: 'text-sm',
    maxText: 'text-[8px]',
    labelText: 'text-[9px]',
    endpointText: 'text-[8px]',
    showSummary: false,
    showEndpoints: false
  }
};

export function CompositeScoreGauge({
  score,
  size = 'large',
  highlight = false,
  className = '',
  scoreTestId,
  gaugeTestId = 'composite-score-gauge'
}: CompositeScoreGaugeProps) {
  const styles = SIZE_STYLES[size];
  const maxScore = Math.max(1, score.maxScore);
  const targetScore = Math.max(0, score.score);
  const targetPercent = Math.max(0, Math.min(100, (targetScore / maxScore) * 100));
  const [animated, setAnimated] = useState(() => ({
    score: targetScore,
    percent: targetPercent
  }));
  const animatedRef = useRef(animated);
  const targetRef = useRef({ score: targetScore, maxScore });
  const frameRef = useRef<number | null>(null);
  const displayScore = Math.max(0, Math.round(animated.score));
  const percent = Math.max(0, Math.min(100, animated.percent));
  const fillClass = score.score <= 0 ? 'stroke-red-500' : highlight ? 'stroke-emerald-500' : 'stroke-brand-blue';
  const textClass = score.score <= 0 ? 'text-red-600' : highlight ? 'text-emerald-600' : scoreTextTone(score.score);

  useEffect(() => {
    const previousTarget = targetRef.current;
    const scoreChanged = previousTarget.score !== targetScore || previousTarget.maxScore !== maxScore;
    targetRef.current = { score: targetScore, maxScore };

    if (!scoreChanged) {
      return;
    }

    if (typeof window === 'undefined' || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      if (frameRef.current !== null) {
        cancelAnimationFrame(frameRef.current);
        frameRef.current = null;
      }
      const next = { score: targetScore, percent: targetPercent };
      animatedRef.current = next;
      setAnimated(next);
      return;
    }

    if (frameRef.current !== null) {
      cancelAnimationFrame(frameRef.current);
    }

    const start = animatedRef.current;
    const startedAt = performance.now();

    const tick = (now: number) => {
      const progress = Math.min(1, (now - startedAt) / GAUGE_ANIMATION_MS);
      const eased = easeOutCubic(progress);
      const next = {
        score: start.score + (targetScore - start.score) * eased,
        percent: start.percent + (targetPercent - start.percent) * eased
      };
      animatedRef.current = next;
      setAnimated(next);

      if (progress < 1) {
        frameRef.current = requestAnimationFrame(tick);
      } else {
        frameRef.current = null;
      }
    };

    frameRef.current = requestAnimationFrame(tick);

    return () => {
      if (frameRef.current !== null) {
        cancelAnimationFrame(frameRef.current);
        frameRef.current = null;
      }
    };
  }, [maxScore, targetPercent, targetScore]);

  return (
    <div
      data-testid={gaugeTestId}
      className={`text-center ${styles.shell} ${className}`}
      aria-label={`종합 점수 ${displayScore}점 / ${score.maxScore.toLocaleString('ko-KR')}점`}
    >
      <div className="relative mx-auto">
        <svg className={`w-full overflow-visible ${styles.svg}`} viewBox="0 0 220 132" role="img" aria-hidden="true">
          <path
            d="M 24 112 A 86 86 0 0 1 196 112"
            fill="none"
            className="stroke-slate-200"
            strokeWidth={styles.strokeWidth}
            strokeLinecap="butt"
            pathLength={100}
          />
          <path
            d="M 24 112 A 86 86 0 0 1 196 112"
            fill="none"
            className={fillClass}
            strokeWidth={styles.strokeWidth}
            strokeLinecap="butt"
            pathLength={100}
            strokeDasharray={`${percent} 100`}
          />
        </svg>

        <div className={`absolute inset-x-0 ${styles.center} px-1`}>
          <div data-testid={scoreTestId} className={`font-black leading-none ${styles.scoreText} ${textClass}`}>
            {displayScore.toLocaleString('ko-KR')}
            <span className={`ml-1 font-black text-slate-400 ${styles.maxText}`}>/ {score.maxScore.toLocaleString('ko-KR')}</span>
          </div>
          <div className={`mt-1 truncate font-black text-commerce-ink ${styles.labelText}`} title={`${score.grade} · ${score.label}`}>
            {score.grade} · {score.label}
          </div>
        </div>
      </div>

      {styles.showEndpoints ? (
        <div className={`-mt-3 flex items-center justify-between px-4 font-bold text-slate-400 ${styles.endpointText}`} aria-hidden="true">
          <span>0</span>
          <span>{score.maxScore.toLocaleString('ko-KR')}</span>
        </div>
      ) : null}

      {styles.showSummary ? (
        <p className="mt-2 break-keep text-[11px] font-bold leading-5 text-slate-500">{score.summary}</p>
      ) : null}
    </div>
  );
}

function easeOutCubic(value: number) {
  return 1 - Math.pow(1 - value, 3);
}

function scoreTextTone(score: number) {
  if (score >= 930) return 'text-brand-blue';
  if (score >= 850) return 'text-commerce-green';
  if (score >= 750) return 'text-commerce-ink';
  if (score >= 600) return 'text-amber-600';
  return 'text-red-600';
}
