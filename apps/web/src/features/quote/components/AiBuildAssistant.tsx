import { type FormEvent, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { BarChart3, Bot, CheckCircle2, Send, ShoppingCart, Sparkles, X } from 'lucide-react';
import { AUTH_CHANGED_EVENT, ApiError, clearToken, getToken } from '../../../lib/api';
import { AI_BUILD_ASSISTANT_OPEN_EVENT, AI_BUILD_ASSISTANT_TOGGLE_EVENT, type AiAssistantOpenDetail } from '../../../lib/events';
import { applyAiBuildToQuoteDraft, getCurrentQuoteDraft } from '../../parts/partsApi';
import {
  AI_ASSISTANT_SESSION_CHANGED_EVENT,
  PART_CATEGORY_LABELS,
  clearLegacyAiStorage,
  createAiMessageId,
  getAiStorageOwnerKey,
  mergeAiBuildHistory,
  normalizeAiBuilds,
  normalizeAiRecommendedBuild,
  readAssistantSession,
  recentBuildsForChatContext,
  saveAssistantSession,
  saveSelectedAiBuild,
  type AiChatMessage,
  type AiPerformanceSimulation,
  type AiRecommendedBuild,
  type AiToolResult,
  type BuildGraphFocus
} from '../aiSelection';
import { buildChat } from '../quoteApi';

type AiBuildAssistantProps = {
  surface?: 'home' | 'self-quote';
};

const LOGIN_REQUIRED_MESSAGE = '로그인이 필요합니다. 다시 로그인해 주세요.';
const GENERIC_SUBMIT_ERROR_MESSAGE = 'AI 추천 API 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.';
const COMMON_QUICK_PROMPTS = [
  { label: '200만원 게이밍 PC', prompt: '200만원으로 게이밍 PC 추천해줘' },
  { label: '견적 마저 채우기', prompt: '지금 견적 기준으로 나머지 부품 채워줘' },
  { label: '성능 비교', prompt: 'CPU를 9700X로 바꾸면 성능 어떻게 돼?' }
];

const ASSISTANT_DESKTOP_QUERY = '(min-width: 768px)';

export function AiBuildAssistant({ surface = 'home' }: AiBuildAssistantProps) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const [open, setOpen] = useState(false);
  const [isDesktopAssistant, setIsDesktopAssistant] = useState(() => (
    typeof window === 'undefined' ? true : window.matchMedia(ASSISTANT_DESKTOP_QUERY).matches
  ));
  const [prompt, setPrompt] = useState('');
  const [session, setSession] = useState(() => readAssistantSession());
  const [isSending, setIsSending] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [applyError, setApplyError] = useState<string | null>(null);
  const [failedBuild, setFailedBuild] = useState<AiRecommendedBuild | null>(null);
  const [applyingBuildId, setApplyingBuildId] = useState<string | null>(null);
  const [pendingSubmit, setPendingSubmit] = useState<string | null>(null);
  const hasToken = Boolean(getToken());
  const quoteDraftQuery = useQuery({
    queryKey: ['quote-draft', 'current'],
    queryFn: getCurrentQuoteDraft,
    enabled: hasToken
  });

  useEffect(() => {
    if (typeof window === 'undefined') return undefined;
    const mediaQuery = window.matchMedia(ASSISTANT_DESKTOP_QUERY);
    const handleChange = () => setIsDesktopAssistant(mediaQuery.matches);
    handleChange();
    mediaQuery.addEventListener('change', handleChange);
    return () => {
      mediaQuery.removeEventListener('change', handleChange);
    };
  }, []);

  useEffect(() => {
    const openAssistant = (event: Event) => {
      const detail = (event as CustomEvent<AiAssistantOpenDetail>).detail;
      setOpen(true);
      if (detail?.prefill) {
        if (detail.autoSubmit) {
          setPendingSubmit(detail.prefill);
        } else {
          setPrompt(detail.prefill);
        }
      }
    };
    const toggleAssistant = () => setOpen((current) => !current);
    window.addEventListener(AI_BUILD_ASSISTANT_OPEN_EVENT, openAssistant);
    window.addEventListener(AI_BUILD_ASSISTANT_TOGGLE_EVENT, toggleAssistant);
    return () => {
      window.removeEventListener(AI_BUILD_ASSISTANT_OPEN_EVENT, openAssistant);
      window.removeEventListener(AI_BUILD_ASSISTANT_TOGGLE_EVENT, toggleAssistant);
    };
  }, []);

  useEffect(() => {
    const shouldReserveSpace = open && isDesktopAssistant;
    document.documentElement.classList.toggle('ai-assistant-open', shouldReserveSpace);
    return () => {
      document.documentElement.classList.remove('ai-assistant-open');
    };
  }, [open, isDesktopAssistant]);

  useEffect(() => {
    const syncSession = () => {
      clearLegacyAiStorage();
      setSession(readAssistantSession());
    };
    syncSession();
    window.addEventListener(AI_ASSISTANT_SESSION_CHANGED_EVENT, syncSession);
    window.addEventListener(AUTH_CHANGED_EVENT, syncSession);
    window.addEventListener('storage', syncSession);
    return () => {
      window.removeEventListener(AI_ASSISTANT_SESSION_CHANGED_EVENT, syncSession);
      window.removeEventListener(AUTH_CHANGED_EVENT, syncSession);
      window.removeEventListener('storage', syncSession);
    };
  }, []);

  useEffect(() => {
    if (!open) return;
    messagesEndRef.current?.scrollIntoView({ block: 'end' });
  }, [open, session.messages.length]);

  useEffect(() => {
    if (!pendingSubmit || isSending) return;
    const text = pendingSubmit;
    setPendingSubmit(null);
    void sendMessage(text);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingSubmit, isSending]);

  async function submitPrompt(event: FormEvent) {
    event.preventDefault();
    await sendMessage(prompt);
  }

  async function sendMessage(rawPrompt: string) {
    const nextPrompt = rawPrompt.trim();
    if (!nextPrompt || isSending) return;

    const ownerKey = getAiStorageOwnerKey();
    if (!getToken() || !ownerKey) {
      setSubmitError(LOGIN_REQUIRED_MESSAGE);
      return;
    }

    const now = new Date().toISOString();
    const baseSession = readAssistantSession(ownerKey);
    const userMessage: AiChatMessage = {
      id: createAiMessageId('user'),
      role: 'user',
      text: nextPrompt,
      createdAt: now,
      kind: 'general'
    };
    const optimisticSession = {
      ...baseSession,
      messages: [...baseSession.messages, userMessage],
      updatedAt: now
    };
    setSession(optimisticSession);
    saveAssistantSession(optimisticSession, ownerKey);
    setPrompt('');
    setSubmitError(null);
    setIsSending(true);

    try {
      // 견적 완성/성능 비교만 현재 견적(드래프트) 문맥이 필요하다. 예산 추천·미지원·명확화는
      // draft 없이 즉시 서버로 보내 체감 지연을 줄인다. 이미 캐시된 draft는 그대로 활용한다.
      const currentQuoteDraft = needsDraftContext(nextPrompt)
        ? quoteDraftQuery.data ?? await queryClient.fetchQuery({
          queryKey: ['quote-draft', 'current'],
          queryFn: getCurrentQuoteDraft
        })
        : quoteDraftQuery.data;
      const response = await buildChat({
        message: nextPrompt,
        currentBuilds: recentBuildsForChatContext(baseSession),
        currentQuoteDraft
      });
      const responseTime = new Date().toISOString();
      const responseBuilds = response.builds?.length ? normalizeAiBuilds(response.builds) : undefined;
      const latestBuilds = responseBuilds ? mergeAiBuildHistory(responseBuilds, baseSession.latestBuilds) : baseSession.latestBuilds;
      const latestGraphFocus = graphFocusFromResponse(response, nextPrompt);
      const assistantMessage: AiChatMessage = {
        id: createAiMessageId(response.answerType.toLowerCase()),
        role: 'assistant',
        text: response.message,
        createdAt: responseTime,
        kind: messageKind(response.answerType),
        builds: responseBuilds,
        simulation: response.simulation ?? undefined,
        warnings: response.warnings ?? []
      };
      const nextSession = {
        messages: [...optimisticSession.messages, assistantMessage],
        latestBuilds,
        savedBuildIds: baseSession.savedBuildIds,
        latestGraphFocus,
        latestActiveBuildId: responseBuilds?.find((build) => build.tier === 'balanced')?.id
          ?? responseBuilds?.[0]?.id
          ?? baseSession.latestActiveBuildId
          ?? latestBuilds[1]?.id
          ?? latestBuilds[0]?.id,
        updatedAt: responseTime
      };
      setSession(nextSession);
      saveAssistantSession(nextSession, ownerKey);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        saveAssistantSession(baseSession, ownerKey);
        clearToken();
        setSession(readAssistantSession(null));
        setSubmitError(LOGIN_REQUIRED_MESSAGE);
        return;
      }
      setSubmitError(GENERIC_SUBMIT_ERROR_MESSAGE);
    } finally {
      setIsSending(false);
    }
  }

  async function selectBuild(build: AiRecommendedBuild) {
    if (applyingBuildId) return;
    const normalizedBuild = normalizeAiRecommendedBuild(build);
    saveSelectedAiBuild(normalizedBuild);
    setApplyError(null);
    setFailedBuild(null);
    setApplyingBuildId(normalizedBuild.id);
    try {
      const appliedDraft = await applyAiBuildToQuoteDraft({
        buildId: normalizedBuild.id,
        conflictPolicy: 'REPLACE',
        items: normalizedBuild.items.map((item) => ({
          partId: item.partId,
          category: item.category,
          quantity: item.quantity
        }))
      });
      queryClient.setQueryData(['quote-draft', 'current'], appliedDraft);
      void queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
      setOpen(false);
      navigate('/self-quote');
    } catch {
      setFailedBuild(normalizedBuild);
      setApplyError('AI 조합을 셀프 견적 장바구니에 적용하지 못했습니다.');
    } finally {
      setApplyingBuildId(null);
    }
  }

  if (!open && isDesktopAssistant) {
    return null;
  }

  if (!open) {
    return (
      <button
        type="button"
        aria-label="AI 견적 챗봇 열기"
        data-testid="ai-chatbot-launcher"
        onClick={() => setOpen(true)}
        className="fixed bottom-5 right-5 z-50 flex h-16 w-16 items-center justify-center rounded-2xl border border-slate-900 bg-slate-950 text-white shadow-2xl transition hover:-translate-y-0.5 hover:bg-slate-800 focus:outline-none focus:ring-4 focus:ring-blue-200 md:hidden"
      >
        <span className="relative grid h-11 w-11 place-items-center rounded-xl bg-white text-slate-950">
          <Bot size={26} />
          <span className="absolute -right-1 -top-1 h-3 w-3 rounded-full border-2 border-slate-950 bg-emerald-400" />
        </span>
      </button>
    );
  }

  const panelClassName = isDesktopAssistant
    ? 'fixed inset-y-0 right-0 z-50 flex h-dvh w-[420px] flex-col overflow-hidden border-l border-slate-200 bg-[#f8fbff] shadow-2xl'
    : 'fixed bottom-4 right-3 z-50 w-[min(calc(100vw-1.5rem),460px)] overflow-hidden rounded-2xl border border-slate-200 bg-[#f8fbff] shadow-2xl';

  return (
    <section
      data-testid="ai-chatbot-panel"
      className={panelClassName}
    >
      <div className="border-b border-slate-200 bg-white px-4 py-3">
        <div className="flex items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-3">
            <div className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-brand-blue text-white shadow-sm">
              <Sparkles size={20} />
            </div>
            <div className="min-w-0">
              <h2 className="truncate text-sm font-black text-commerce-ink">AI 견적 어시스턴트</h2>
              <p className="truncate text-xs font-bold text-slate-500">{surface === 'home' ? '내부 견적 자산 기준 · 호환성 자동 체크' : '현재 견적 기준 · 부품 교체 자동 적용'}</p>
            </div>
          </div>
          <button
            type="button"
            aria-label="AI 견적 챗봇 닫기"
            onClick={() => setOpen(false)}
            className="grid h-9 w-9 place-items-center rounded-md border border-slate-200 bg-white text-slate-500 transition hover:border-slate-300 hover:text-commerce-ink focus:outline-none focus:ring-4 focus:ring-blue-100"
          >
            <X size={17} />
          </button>
        </div>
      </div>

      <div className={`${isDesktopAssistant ? 'min-h-0 flex-1' : 'max-h-[78vh]'} flex flex-col`}>
        <div className="border-b border-slate-100 bg-[#f8fbff] px-4 py-3">
          <div className="mb-2 text-[11px] font-black text-slate-400">이렇게 물어보세요</div>
          <div className="flex flex-wrap gap-2">
            {COMMON_QUICK_PROMPTS.map((example) => (
              <button
                key={example.label}
                type="button"
                onClick={() => setPrompt(example.prompt)}
                className="rounded-full border border-slate-200 bg-white px-3 py-1.5 text-[11px] font-black text-slate-600 shadow-sm transition hover:border-brand-blue hover:text-brand-blue focus:outline-none focus:ring-4 focus:ring-blue-100"
              >
                {example.label}
              </button>
            ))}
          </div>
        </div>

        <div data-testid="ai-chat-messages" className="min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-4">
          {session.messages.map((message) => (
            <ChatMessage
              key={message.id}
              message={message}
              onSelectBuild={selectBuild}
            />
          ))}
          {isSending ? (
            <div className="rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm font-bold text-slate-500 shadow-sm">
              서버 DB에서 추천 조합을 계산하는 중입니다.
            </div>
          ) : null}
          <div ref={messagesEndRef} />
        </div>

        <form onSubmit={submitPrompt} className="border-t border-slate-200 bg-white p-3">
          {submitError ? (
            <div role="alert" className="mb-2 rounded-md border border-red-100 bg-red-50 px-3 py-2 text-xs font-bold text-red-700">
              {submitError}
            </div>
          ) : null}
          {applyError ? (
            <div role="alert" className="mb-2 flex flex-col gap-2 rounded-md border border-red-100 bg-red-50 px-3 py-2 text-xs font-bold text-red-700 sm:flex-row sm:items-center sm:justify-between">
              <span>{applyError}</span>
              <button
                type="button"
                onClick={() => failedBuild && selectBuild(failedBuild)}
                disabled={!failedBuild || Boolean(applyingBuildId)}
                className="min-h-8 rounded bg-red-600 px-3 text-white disabled:bg-red-200"
              >
                재시도
              </button>
            </div>
          ) : null}
          <label className="sr-only" htmlFor="ai-build-chat-input">AI 챗봇에게 PC 사양 질문</label>
          <div className="flex gap-2 rounded-full border border-slate-200 bg-slate-50 p-1.5 shadow-inner focus-within:border-brand-blue focus-within:ring-4 focus-within:ring-blue-100">
            <input
              id="ai-build-chat-input"
              aria-label="AI 챗봇에게 PC 사양 질문"
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              disabled={isSending}
              placeholder="PC 견적을 물어보세요..."
              className="min-w-0 flex-1 bg-transparent px-3 text-sm font-medium text-slate-900 outline-none placeholder:text-slate-400"
            />
            <button
              type="submit"
              aria-label="질문 보내기"
              disabled={!prompt.trim() || isSending}
              className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-brand-blue text-white transition hover:bg-blue-700 disabled:bg-slate-300"
            >
              <Send size={17} />
            </button>
          </div>
          <div className="mt-2 flex items-center gap-2 text-[11px] font-bold text-slate-500">
            <CheckCircle2 size={14} className="text-commerce-green" />
            추천은 서버 DB/룰 기반이며 대화 히스토리는 브라우저 세션에만 저장합니다.
          </div>
        </form>
      </div>
    </section>
  );
}

// 서버가 현재 견적(드래프트) 문맥을 실제로 쓰는 요청만 draft를 먼저 확보한다.
// 백엔드 BuildChatIntentRouter의 시뮬레이션/견적완성 판정 어휘와 맞춘다.
function needsDraftContext(prompt: string) {
  const normalized = prompt.toLowerCase().replace(/\s+/g, '');
  const completionLike = /지금|현재|이견적|그래프|나머지|마저|채워|완성/.test(normalized);
  const simulationLike = /바꾸면|바꿨|교체하면|교체시|넣으면|달면|끼우면|끼면|박으면|올리면|올렸|내리면|내려|낮추면|늘리면|줄이면|갈아|넘어가면|업그레이드하면|다운그레이드하면|프레임|fps|성능|체감|비교/.test(normalized);
  return completionLike || simulationLike;
}

function messageKind(answerType: 'BUDGET' | 'PART' | 'GENERAL'): AiChatMessage['kind'] {
  if (answerType === 'BUDGET') return 'budget';
  if (answerType === 'PART') return 'part';
  return 'general';
}

function graphFocusFromResponse(
  response: {
    answerType: 'BUDGET' | 'PART' | 'GENERAL';
    warnings?: string[];
  },
  prompt: string
): BuildGraphFocus {
  if (response.answerType === 'BUDGET') {
    return {
      mode: 'BUILD_OVERVIEW',
      tool: toolFromPrompt(prompt)
    };
  }
  return {
    mode: response.warnings?.length || /왜|안돼|안 돼|호환|전력|파워|크기|장착/.test(prompt) ? 'ISSUE_PATH' : 'BUILD_OVERVIEW',
    tool: toolFromPrompt(prompt)
  };
}

function toolFromPrompt(prompt: string): BuildGraphFocus['tool'] {
  if (/파워|전력|w\b|W\b|psu/i.test(prompt)) return 'power';
  if (/케이스|크기|장착|길이|쿨러|높이/.test(prompt)) return 'size';
  if (/호환|소켓|메인보드|보드|ram|DDR/i.test(prompt)) return 'compatibility';
  if (/성능|게임|QHD|FPS|개발|AI|CUDA/i.test(prompt)) return 'performance';
  if (/가격|예산|만원|원/.test(prompt)) return 'price';
  return undefined;
}

function ChatMessage({
  message,
  onSelectBuild
}: {
  message: AiChatMessage;
  onSelectBuild: (build: AiRecommendedBuild) => void;
}) {
  const isUser = message.role === 'user';

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-full ${isUser ? 'w-fit max-w-[86%]' : 'w-full'}`}>
        <div className={`rounded-2xl px-3 py-2 text-sm leading-6 shadow-sm ${isUser ? 'bg-brand-blue text-white' : 'border border-slate-200 bg-white text-slate-700'}`}>
          {!isUser ? (
            <div className="mb-1 flex items-center gap-2 text-[11px] font-black text-brand-blue">
              <span className="grid h-5 w-5 place-items-center rounded-full bg-blue-50 text-brand-blue">
                <Sparkles size={12} />
              </span>
              {message.simulation ? '성능 시뮬레이션' : 'AI 견적 어시스턴트'}
            </div>
          ) : null}
          <p className="break-keep">{message.text}</p>
        </div>

        {message.simulation ? (
          <SimulationResultCard simulation={message.simulation} />
        ) : null}

        {message.builds ? (
          <div className="mt-2 grid gap-2">
            {message.builds.map((build) => (
              <CompactBuildCard key={`${message.id}-${build.id}`} build={build} onSelectBuild={onSelectBuild} />
            ))}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function SimulationResultCard({ simulation }: { simulation: AiPerformanceSimulation }) {
  const fpsRows = simulation.fpsComparisons ?? [];
  const specRows = simulation.specComparisons ?? [];
  const score = simulation.scoreComparison;
  const maxFps = Math.max(
    1,
    ...fpsRows.flatMap((row) => [row.currentFps ?? 0, row.targetFps ?? 0])
  );

  return (
    <section className="mt-2 rounded-lg border border-blue-100 bg-blue-50 p-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2 text-xs font-black text-brand-blue">
            <BarChart3 size={14} />
            성능 시뮬레이션
          </div>
          <div className="mt-1 break-keep text-sm font-black text-commerce-ink">
            {simulation.currentPart.name} → {simulation.targetPart.name}
          </div>
        </div>
        <span className="rounded bg-white px-2 py-1 text-[11px] font-black text-slate-600">
          {PART_CATEGORY_LABELS[simulation.category]}
        </span>
      </div>

      {score ? (
        <div className="mt-3 rounded-md bg-white p-3">
          <div className="flex items-center justify-between gap-2 text-xs">
            <span className="font-black text-slate-700">{score.label}</span>
            <span className={`font-black ${score.delta && score.delta > 0 ? 'text-commerce-green' : score.delta && score.delta < 0 ? 'text-commerce-sale' : 'text-slate-500'}`}>
              {formatSigned(score.delta, '점')}
            </span>
          </div>
          <div className="mt-2 grid gap-2">
            <ComparisonBar label="현재" value={score.currentScore} max={100} tone="slate" />
            <ComparisonBar label="변경 후" value={score.targetScore} max={100} tone="blue" />
          </div>
        </div>
      ) : null}

      {fpsRows.length ? (
        <div className="mt-3 overflow-hidden rounded-md border border-blue-100 bg-white">
          <div className="grid grid-cols-[1.2fr_0.8fr_1.2fr] gap-2 border-b border-blue-50 px-3 py-2 text-[11px] font-black text-slate-500">
            <span>게임/해상도</span>
            <span className="text-right">FPS 변화</span>
            <span>비교 막대</span>
          </div>
          <div className="divide-y divide-blue-50">
            {fpsRows.map((row) => (
              <div key={`${row.gameTitle}-${row.resolution}-${row.graphicsPreset ?? ''}`} className="grid grid-cols-[1.2fr_0.8fr_1.2fr] gap-2 px-3 py-2 text-xs">
                <div className="min-w-0">
                  <div className="break-keep font-black text-commerce-ink">{row.gameTitle}</div>
                  <div className="mt-0.5 text-[11px] font-bold text-slate-500">{row.resolution}{row.graphicsPreset ? ` · ${row.graphicsPreset}` : ''}</div>
                </div>
                <div className="text-right font-black text-slate-700">
                  <div>{formatFps(row.currentFps)} → {formatFps(row.targetFps)}</div>
                  <div className={`${row.deltaFps && row.deltaFps > 0 ? 'text-commerce-green' : row.deltaFps && row.deltaFps < 0 ? 'text-commerce-sale' : 'text-slate-400'}`}>
                    {formatSigned(row.deltaFps, 'fps')}
                  </div>
                </div>
                <div className="grid content-center gap-1">
                  <MiniBar value={row.currentFps} max={maxFps} className="bg-slate-300" />
                  <MiniBar value={row.targetFps} max={maxFps} className="bg-brand-blue" />
                </div>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {specRows.length ? (
        <div className="mt-3 grid gap-2 sm:grid-cols-2">
          {specRows.map((row) => (
            <div key={row.label} className="rounded-md border border-blue-100 bg-white p-2 text-xs">
              <div className="font-black text-commerce-ink">{row.label}</div>
              <div className="mt-1 flex items-center justify-between gap-2 text-slate-600">
                <span className="truncate">{row.currentValue ?? '-'}</span>
                <span className="font-black text-slate-400">→</span>
                <span className="truncate font-black text-brand-blue">{row.targetValue ?? '-'}</span>
              </div>
              {row.deltaText ? <div className="mt-1 text-[11px] font-bold text-commerce-green">{row.deltaText}</div> : null}
            </div>
          ))}
        </div>
      ) : null}

      {simulation.warnings?.length ? (
        <div className="mt-3 rounded-md bg-amber-50 px-3 py-2 text-xs font-bold leading-5 text-amber-700">
          {simulation.warnings[0]}
        </div>
      ) : null}
      <p className="mt-3 break-keep text-[11px] font-bold leading-5 text-slate-500">
        {simulation.disclaimer
          ?? '본 수치는 내부 벤치마크 DB 기준 참고용 추정치입니다. 실제 성능은 게임 버전, 그래픽 옵션, 드라이버, 해상도, 냉각·전원 환경에 따라 달라질 수 있습니다.'}
      </p>
    </section>
  );
}

function ComparisonBar({ label, value, max, tone }: { label: string; value?: number | null; max: number; tone: 'slate' | 'blue' }) {
  return (
    <div className="grid grid-cols-[48px_1fr_44px] items-center gap-2 text-[11px] font-bold text-slate-500">
      <span>{label}</span>
      <MiniBar value={value} max={max} className={tone === 'blue' ? 'bg-brand-blue' : 'bg-slate-300'} />
      <span className="text-right text-commerce-ink">{formatPlainNumber(value)}</span>
    </div>
  );
}

function MiniBar({ value, max, className }: { value?: number | null; max: number; className: string }) {
  const width = Math.max(4, Math.min(100, Math.round(((value ?? 0) / Math.max(1, max)) * 100)));
  return (
    <div className="h-2 overflow-hidden rounded-full bg-slate-100">
      <div className={`h-full rounded-full ${className}`} style={{ width: `${width}%` }} />
    </div>
  );
}

function CompactBuildCard({
  build,
  onSelectBuild
}: {
  build: AiRecommendedBuild;
  onSelectBuild: (build: AiRecommendedBuild) => void;
}) {
  const primaryItems = build.items.slice(0, 5);

  return (
    <article className="rounded-2xl border border-slate-200 bg-white p-3 shadow-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="rounded-full bg-brand-blue px-2.5 py-1 text-[11px] font-black text-white">{build.label}</span>
        {build.appliedPartCategories.map((category) => (
          <span key={category} className="rounded-full bg-emerald-50 px-2.5 py-1 text-[11px] font-black text-emerald-700">
            {PART_CATEGORY_LABELS[category]} 반영됨
          </span>
        ))}
      </div>
      <div className="mt-3 flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h3 className="text-sm font-black leading-5 text-commerce-ink">{build.title}</h3>
          <p className="mt-1 line-clamp-2 break-keep text-xs leading-5 text-slate-500">{build.summary}</p>
        </div>
        <div className="shrink-0 text-left sm:text-right">
          <div className="text-base font-black text-brand-blue">{build.totalPrice.toLocaleString()}원</div>
          <div className="text-[11px] font-bold text-slate-500">{build.items.length}개 부품</div>
        </div>
      </div>
      {build.toolResults?.length ? (
        <div className="mt-3 flex flex-wrap gap-1.5" aria-label="Tool 검증 결과">
          {build.toolResults.map((result) => (
            <span
              key={`${result.tool}-${result.status}`}
              title={result.summary}
              className={`rounded-full border px-2 py-1 text-[11px] font-black ${toolStatusChipClass(result.status)}`}
            >
              {toolDisplayLabel(result.tool)} {toolStatusLabel(result.status)}
            </span>
          ))}
        </div>
      ) : null}
      <div className="mt-3 grid gap-1.5 text-xs">
        {primaryItems.map((item) => (
          <div key={item.partId} className="grid grid-cols-[56px_minmax(0,1fr)] gap-2 rounded-lg bg-slate-50 px-2.5 py-1.5">
            <span className="font-black text-brand-blue">{PART_CATEGORY_LABELS[item.category]}</span>
            <span className="truncate font-semibold text-slate-700">{item.name}</span>
          </div>
        ))}
      </div>
      <button
        type="button"
        onClick={() => onSelectBuild(build)}
        className="mt-3 flex w-full min-h-10 items-center justify-center gap-2 rounded-full border border-blue-200 bg-white px-3 text-xs font-black text-brand-blue transition hover:border-brand-blue hover:bg-blue-50 focus:outline-none focus:ring-4 focus:ring-blue-100"
      >
        <ShoppingCart size={15} />
        이 조합으로 셀프 견적 보기
      </button>
    </article>
  );
}

function toolDisplayLabel(tool: AiToolResult['tool']) {
  const labels: Record<string, string> = {
    compatibility: '호환성',
    power: '전력',
    size: '규격',
    performance: '성능',
    price: '가격'
  };
  return labels[tool] ?? tool;
}

function toolStatusLabel(status: AiToolResult['status']) {
  if (status === 'PASS') return '호환';
  if (status === 'WARN') return '간섭 주의';
  return '안 맞음';
}

function toolStatusChipClass(status: AiToolResult['status']) {
  if (status === 'PASS') return 'border-emerald-200 bg-emerald-50 text-emerald-700';
  if (status === 'WARN') return 'border-amber-200 bg-amber-50 text-amber-700';
  return 'border-red-200 bg-red-50 text-red-700';
}

function formatPlainNumber(value?: number | null) {
  if (value === null || value === undefined) return '-';
  return Math.abs(value - Math.round(value)) < 0.05 ? String(Math.round(value)) : value.toFixed(1);
}

function formatFps(value?: number | null) {
  return value === null || value === undefined ? '-' : `${formatPlainNumber(value)}fps`;
}

function formatSigned(value?: number | null, unit = '') {
  if (value === null || value === undefined) return '-';
  const prefix = value > 0 ? '+' : '';
  return `${prefix}${formatPlainNumber(value)}${unit}`;
}
