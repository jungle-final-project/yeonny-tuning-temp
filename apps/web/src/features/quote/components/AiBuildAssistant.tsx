import { type FormEvent, memo, useCallback, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { BarChart3, Bot, CheckCircle2, Send, ShoppingCart, Sparkles, X } from 'lucide-react';
import { useLockedPageScroll } from '../../../hooks/useHiddenPageScrollbar';
import { AUTH_CHANGED_EVENT, ApiError, clearToken, getToken } from '../../../lib/api';
import { AI_BUILD_ASSISTANT_CLOSE_EVENT, AI_BUILD_ASSISTANT_OPEN_EVENT, AI_BUILD_ASSISTANT_TOGGLE_EVENT, SUPPORT_CHAT_CLOSE_EVENT, SUPPORT_CHAT_OPEN_EVENT, requestPerfCompare, setAiAssistantOpen, type AiAssistantOpenDetail } from '../../../lib/events';
import { applyAiBuildToQuoteDraft, getCurrentQuoteDraft, putQuoteDraftItem } from '../../parts/partsApi';
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
  resetAssistantConversation,
  saveAssistantSession,
  saveSelectedAiBuild,
  type AiAssessmentContext,
  type AiBuildAssessment,
  type AiChatMessage,
  type AiBoardFocus,
  type AiPerformanceSimulation,
  type AiQuickReplyCommand,
  type AiRecommendedBuild,
  type AiToolResult,
  type BuildGraphFocus,
  type PartCategory
} from '../aiSelection';
import { buildChat, resolveBuildGraph } from '../quoteApi';

type AiBuildAssistantProps = {
  surface?: 'home' | 'self-quote';
  variant?: 'floating' | 'embedded';
  onBoardFocus?: (focus: AiBoardFocus) => void;
};

type AiChatMessageSize = 'default' | 'large';

type CenterScrollbarState = {
  canScroll: boolean;
  visible: boolean;
  thumbHeight: number;
  thumbTop: number;
};

const LOGIN_REQUIRED_MESSAGE = '로그인이 필요합니다. 다시 로그인해 주세요.';
const GENERIC_SUBMIT_ERROR_MESSAGE = 'AI 추천 API 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.';
const COMMON_QUICK_PROMPTS = [
  { label: '200만원 게이밍 PC', prompt: '200만원으로 게이밍 PC 추천해줘' },
  { label: '견적 마저 채우기', prompt: '지금 견적 기준으로 나머지 부품 채워줘' },
  { label: '성능 비교', prompt: 'CPU를 9700X로 바꾸면 성능 어떻게 돼?' }
];

const ASSISTANT_DESKTOP_QUERY = '(min-width: 768px)';
const CENTER_SCROLLBAR_TRACK_TOP = 8;
const CENTER_SCROLLBAR_TRACK_BOTTOM = 12;
const CENTER_SCROLLBAR_MIN_THUMB_HEIGHT = 32;
const CENTER_SCROLLBAR_HIDE_DELAY_MS = 700;

export function AiBuildAssistant({ surface = 'home', variant = 'floating', onBoardFocus }: AiBuildAssistantProps) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const centerMessagesScrollRef = useRef<HTMLDivElement | null>(null);
  const centerScrollbarHideTimerRef = useRef<number | null>(null);
  const centerScrollbarVisibleRef = useRef(false);
  const isEmbedded = variant === 'embedded';
  const [open, setOpen] = useState(isEmbedded);
  const [placement, setPlacement] = useState<NonNullable<AiAssistantOpenDetail['placement']>>('side');
  const isPanelOpen = isEmbedded || open;
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
  const [runningQuickReplyCommandId, setRunningQuickReplyCommandId] = useState<string | null>(null);
  const [pendingSubmit, setPendingSubmit] = useState<{ text: string; assessmentContext?: AiAssessmentContext } | null>(null);
  const [centerScrollbar, setCenterScrollbar] = useState<CenterScrollbarState>({
    canScroll: false,
    visible: false,
    thumbHeight: CENTER_SCROLLBAR_MIN_THUMB_HEIGHT,
    thumbTop: 0
  });
  // 직전 응답이 되묻기였다면 다음 전송에 원 요청을 에코해 서버가 두 문장을 합성하게 한다(1회 왕복).
  const [pendingClarification, setPendingClarification] = useState<{ originalMessage: string } | null>(null);
  const hasToken = Boolean(getToken());
  useLockedPageScroll(open && placement === 'center');
  // 패널을 실제로 연 뒤에만 현재 견적(드래프트)을 미리 받는다. 전역 렌더라 로그인만으로
  // 모든 페이지에서 draft API가 선행되던 것을 없앤다. 완성/시뮬레이션 요청은 패널 open 시점에
  // 이미 prefetch돼 있어 체감 저하가 없고, 예산/미지원/명확화는 draft 없이 즉시 전송된다.
  const quoteDraftQuery = useQuery({
    queryKey: ['quote-draft', 'current'],
    queryFn: getCurrentQuoteDraft,
    enabled: hasToken && isPanelOpen
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
    function resetCenteredConversation() {
      const ownerKey = getAiStorageOwnerKey();
      const nextSession = resetAssistantConversation(ownerKey);
      setSession(nextSession);
      setPrompt('');
      setSubmitError(null);
      setApplyError(null);
      setFailedBuild(null);
      setPendingClarification(null);
      setCenterScrollbar((current) => ({ ...current, canScroll: false, visible: false, thumbTop: 0 }));
    }

    const openAssistant = (event: Event) => {
      const detail = (event as CustomEvent<AiAssistantOpenDetail>).detail;
      const nextPlacement = detail?.placement ?? 'side';
      setPlacement(nextPlacement);
      if (nextPlacement === 'center') {
        resetCenteredConversation();
      }
      if (!isDesktopAssistant) {
        window.dispatchEvent(new Event(SUPPORT_CHAT_CLOSE_EVENT));
      }
      setOpen(true);
      if (detail?.prefill) {
        if (detail.autoSubmit) {
          setPendingSubmit({ text: detail.prefill, assessmentContext: detail.assessmentContext });
          window.requestAnimationFrame(() => {
            document.querySelector('[data-testid="ai-chatbot-panel"]')?.scrollIntoView({
              behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
              block: 'nearest'
            });
          });
        } else {
          setPrompt(detail.prefill);
        }
      }
    };
    const toggleAssistant = () => setOpen((current) => {
      const nextOpen = !current;
      if (nextOpen) {
        setPlacement('side');
      }
      if (nextOpen && !isDesktopAssistant) {
        window.dispatchEvent(new Event(SUPPORT_CHAT_CLOSE_EVENT));
      }
      return nextOpen;
    });
    const closeAssistant = () => {
      if (!isEmbedded) {
        setOpen(false);
      }
    };
    const closeForSupportChat = () => {
      if (!isDesktopAssistant) {
        setOpen(false);
      }
    };
    window.addEventListener(AI_BUILD_ASSISTANT_OPEN_EVENT, openAssistant);
    window.addEventListener(AI_BUILD_ASSISTANT_TOGGLE_EVENT, toggleAssistant);
    window.addEventListener(AI_BUILD_ASSISTANT_CLOSE_EVENT, closeAssistant);
    window.addEventListener(SUPPORT_CHAT_OPEN_EVENT, closeForSupportChat);
    return () => {
      window.removeEventListener(AI_BUILD_ASSISTANT_OPEN_EVENT, openAssistant);
      window.removeEventListener(AI_BUILD_ASSISTANT_TOGGLE_EVENT, toggleAssistant);
      window.removeEventListener(AI_BUILD_ASSISTANT_CLOSE_EVENT, closeAssistant);
      window.removeEventListener(SUPPORT_CHAT_OPEN_EVENT, closeForSupportChat);
    };
  }, [isDesktopAssistant, isEmbedded]);

  useEffect(() => {
    const shouldReserveSpace = !isEmbedded && open && isDesktopAssistant && placement === 'side';
    document.documentElement.classList.toggle('ai-assistant-open', shouldReserveSpace);
    return () => {
      document.documentElement.classList.remove('ai-assistant-open');
    };
  }, [open, isDesktopAssistant, placement, isEmbedded]);

  useEffect(() => {
    setAiAssistantOpen(!isEmbedded && open);
  }, [open, isEmbedded]);

  useEffect(() => {
    return () => setAiAssistantOpen(false);
  }, []);

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
    if (!isPanelOpen) return;
    messagesEndRef.current?.scrollIntoView({ block: 'end' });
  }, [isPanelOpen, session.messages.length]);

  const updateCenterScrollbar = useCallback((visible: boolean) => {
    centerScrollbarVisibleRef.current = visible;
    const element = centerMessagesScrollRef.current;
    if (!element) {
      centerScrollbarVisibleRef.current = false;
      setCenterScrollbar((current) => ({ ...current, canScroll: false, visible: false }));
      return;
    }

    const scrollableHeight = element.scrollHeight - element.clientHeight;
    if (scrollableHeight <= 1) {
      centerScrollbarVisibleRef.current = false;
      setCenterScrollbar((current) => ({ ...current, canScroll: false, visible: false, thumbTop: 0 }));
      return;
    }

    const trackHeight = Math.max(
      CENTER_SCROLLBAR_MIN_THUMB_HEIGHT,
      element.clientHeight - CENTER_SCROLLBAR_TRACK_TOP - CENTER_SCROLLBAR_TRACK_BOTTOM
    );
    const thumbHeight = Math.max(
      CENTER_SCROLLBAR_MIN_THUMB_HEIGHT,
      Math.round((element.clientHeight / element.scrollHeight) * trackHeight)
    );
    const availableTravel = Math.max(0, trackHeight - thumbHeight);
    const thumbTop = Math.round((element.scrollTop / scrollableHeight) * availableTravel);

    setCenterScrollbar({
      canScroll: true,
      visible,
      thumbHeight,
      thumbTop
    });
  }, []);

  const revealCenterScrollbar = useCallback(() => {
    updateCenterScrollbar(true);
    if (centerScrollbarHideTimerRef.current !== null) {
      window.clearTimeout(centerScrollbarHideTimerRef.current);
    }
    centerScrollbarHideTimerRef.current = window.setTimeout(() => {
      updateCenterScrollbar(false);
      centerScrollbarHideTimerRef.current = null;
    }, CENTER_SCROLLBAR_HIDE_DELAY_MS);
  }, [updateCenterScrollbar]);

  useEffect(() => {
    return () => {
      if (centerScrollbarHideTimerRef.current !== null) {
        window.clearTimeout(centerScrollbarHideTimerRef.current);
      }
    };
  }, []);

  useEffect(() => {
    if (!open || placement !== 'center') {
      centerScrollbarVisibleRef.current = false;
      setCenterScrollbar((current) => ({ ...current, visible: false }));
      return;
    }

    const frameId = window.requestAnimationFrame(() => updateCenterScrollbar(false));
    return () => window.cancelAnimationFrame(frameId);
  }, [open, placement, session.messages.length, updateCenterScrollbar]);

  useEffect(() => {
    if (!pendingSubmit || isSending) return;
    const submission = pendingSubmit;
    setPendingSubmit(null);
    void sendMessage(submission.text, submission.assessmentContext);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingSubmit, isSending]);

  async function submitPrompt(event: FormEvent) {
    event.preventDefault();
    await sendMessage(prompt);
  }

  async function sendMessage(rawPrompt: string, assessmentContext?: AiAssessmentContext) {
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
        : undefined;
      const response = await buildChat({
        message: nextPrompt,
        currentBuilds: recentBuildsForChatContext(baseSession),
        currentQuoteDraft,
        uiContext: surface === 'self-quote'
          ? { surface: 'SELF_QUOTE', capabilities: ['BOARD_PART_FOCUS'] }
          : { surface: 'HOME', capabilities: [] },
        assessmentContext,
        clarificationContext: pendingClarification ?? undefined
      });
      const boardFocus = normalizeBoardFocus(response.boardFocus);
      if (boardFocus && onBoardFocus) {
        onBoardFocus(boardFocus);
      }
      setPendingClarification(
        response.clarification?.originalMessage
          ? { originalMessage: response.clarification.originalMessage }
          : null
      );
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
        buildAssessment: response.buildAssessment ?? undefined,
        warnings: response.warnings ?? [],
        quickReplies: response.quickReplies ?? undefined,
        quickReplyCommands: response.quickReplyCommands ?? undefined
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

  const appendAssistantStatus = useCallback((text: string, warnings: string[] = []) => {
    const ownerKey = getAiStorageOwnerKey();
    if (!ownerKey) return;
    const baseSession = readAssistantSession(ownerKey);
    const createdAt = new Date().toISOString();
    const nextSession = {
      ...baseSession,
      messages: [...baseSession.messages, {
        id: createAiMessageId('draft-action'),
        role: 'assistant' as const,
        text,
        createdAt,
        kind: 'part' as const,
        warnings
      }],
      updatedAt: createdAt
    };
    setSession(nextSession);
    saveAssistantSession(nextSession, ownerKey);
  }, []);

  // 일반 칩은 기존처럼 자연어를 재전송한다. 다만 구체 RAM/SSD 상품 칩은 사용자가 이미 상품을
  // 골랐으므로 LLM/Tool 미리보기를 다시 태우지 않고 quote draft API로 바로 추가한다.
  const handleQuickReply = useCallback(async (
    reply: string,
    command?: AiQuickReplyCommand,
    messageId?: string
  ) => {
    if (!command) {
      setPendingSubmit({ text: reply });
      return;
    }
    const commandId = `${messageId ?? 'quick-reply'}:${command.partId}`;
    if (runningQuickReplyCommandId) return;

    setRunningQuickReplyCommandId(commandId);
    try {
      // 캐시된 draft가 아니라 버튼을 누른 순간의 최신 수량을 읽어, 같은 RAM/SSD를 +1 한다.
      const currentDraft = await getCurrentQuoteDraft();
      const existingItem = currentDraft.items.find((item) => item.partId === command.partId);
      const nextQuantity = (existingItem?.quantity ?? 0) + command.quantityDelta;
      if (nextQuantity > 9) {
        appendAssistantStatus(`${command.partName}은(는) 최대 9개까지 담을 수 있습니다.`);
        return;
      }

      const updatedDraft = await putQuoteDraftItem(command.partId, nextQuantity);
      queryClient.setQueryData(['quote-draft', 'current'], updatedDraft);
      void queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
      void queryClient.invalidateQueries({ queryKey: ['parts', 'slot-candidates'] });
      appendAssistantStatus(`${command.partName} 추가됨. 현재 수량: ${nextQuantity}개입니다.`);

      // 직접 선택한 다중 상품은 저장을 막지 않는다. 다만 이후 graph 검사에서 FAIL이면 채팅에도
      // 명확히 남겨 사용자가 견적 화면에서 조정할 수 있게 한다. 검사 실패 자체는 저장 실패가 아니다.
      void resolveBuildGraph({ source: 'QUOTE_DRAFT_CURRENT' })
        .then((graph) => {
          if (graph.toolResults.some((result) => result.status === 'FAIL')) {
            appendAssistantStatus(
              `${command.partName}은(는) 추가됐지만 현재 견적에 호환성 확인이 필요한 항목이 있습니다. 구매 전 견적 화면에서 조정해 주세요.`,
              ['DRAFT_COMPATIBILITY_WARNING']
            );
          }
        })
        .catch(() => {
          appendAssistantStatus(`${command.partName} 추가됨. 호환성 상태는 견적 화면에서 다시 확인해 주세요.`);
        });
    } catch {
      appendAssistantStatus(`${command.partName} 추가 실패. 현재 견적은 변경되지 않았습니다.`);
    } finally {
      setRunningQuickReplyCommandId(null);
    }
  }, [appendAssistantStatus, queryClient, runningQuickReplyCommandId]);

  // 새 메시지 추가로 리스트가 리렌더될 때 ChatMessage memo가 유지되도록 참조를 안정화한다.
  const selectBuild = useCallback(async (build: AiRecommendedBuild) => {
    if (applyingBuildId) return;
    const normalizedBuild = normalizeAiRecommendedBuild(build);
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
      // 적용이 성공한 뒤에만 선택 빌드를 저장한다. 실패 시 /self-quote 패널이 미적용 빌드를 보여주는 불일치를 막는다.
      saveSelectedAiBuild(normalizedBuild);
      queryClient.setQueryData(['quote-draft', 'current'], appliedDraft);
      void queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
      if (!isEmbedded) {
        setOpen(false);
      }
      navigate('/self-quote');
    } catch {
      setFailedBuild(normalizedBuild);
      setApplyError('AI 조합을 셀프 견적 장바구니에 적용하지 못했습니다.');
    } finally {
      setApplyingBuildId(null);
    }
  }, [applyingBuildId, queryClient, navigate]);

  if (!isPanelOpen && isDesktopAssistant) {
    return null;
  }

  if (!isPanelOpen) {
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

  const isCenteredAssistant = !isEmbedded && placement === 'center';
  if (isCenteredAssistant) {
    const centeredMessages = session.messages.filter((message) => message.id !== 'ai-intro');
    const hasMessages = centeredMessages.length > 0;
    const centeredPromptForm = (
      <form onSubmit={submitPrompt} className="mx-auto w-full max-w-[896px]">
        {submitError ? (
          <div role="alert" className="mb-3 rounded-xl bg-red-50 px-4 py-3 text-base font-bold text-red-700">
            {submitError}
          </div>
        ) : null}
        {applyError ? (
          <div role="alert" className="mb-3 flex flex-col gap-3 rounded-xl bg-red-50 px-4 py-3 text-base font-bold text-red-700 sm:flex-row sm:items-center sm:justify-between">
            <span>{applyError}</span>
            <button
              type="button"
              onClick={() => failedBuild && selectBuild(failedBuild)}
              disabled={!failedBuild || Boolean(applyingBuildId)}
              className="min-h-11 rounded bg-red-600 px-4 text-white disabled:bg-red-200"
            >
              재시도
            </button>
          </div>
        ) : null}
        <label className="sr-only" htmlFor="ai-build-chat-input">AI에게 PC 견적 질문</label>
        <div className="flex min-h-[78px] items-center gap-3 rounded-full border border-slate-200 bg-white px-5 py-3 shadow-[0_22px_70px_rgba(15,23,42,0.14)] focus-within:border-slate-400 focus-within:ring-4 focus-within:ring-slate-100">
          <input
            id="ai-build-chat-input"
            aria-label="AI에게 PC 견적 질문"
            value={prompt}
            onChange={(event) => setPrompt(event.target.value)}
            disabled={isSending}
            placeholder="예산, 용도, 원하는 게임을 입력해 주세요"
            className="min-w-0 flex-1 bg-transparent px-3 text-[20px] font-medium leading-8 text-slate-900 outline-none placeholder:text-slate-400"
          />
          <button
            type="submit"
            aria-label="질문 보내기"
            disabled={!prompt.trim() || isSending}
            className="grid h-14 w-14 shrink-0 place-items-center rounded-full bg-commerce-ink text-white transition hover:bg-slate-700 disabled:bg-slate-300"
          >
            <Send size={24} />
          </button>
        </div>
      </form>
    );
    const centeredCloseButton = (
      <button
        type="button"
        data-testid="ai-chat-close-button"
        aria-label="AI 견적 어시스턴트 닫기"
        onClick={() => setOpen(false)}
        className="absolute right-0 -top-16 z-10 grid h-14 w-14 place-items-center rounded-full bg-white text-slate-600 shadow-lg transition hover:bg-slate-100 hover:text-commerce-ink focus:outline-none focus:ring-4 focus:ring-blue-100 sm:-right-16 sm:top-0"
      >
        <X size={25} />
      </button>
    );

    return (
      <section
        data-testid="ai-chatbot-modal"
        className="fixed inset-0 z-50 flex min-h-dvh bg-slate-950/60 px-4 py-6"
        role="dialog"
        aria-modal="true"
        aria-label="AI 견적 어시스턴트"
        onWheel={(event) => event.stopPropagation()}
        onTouchMove={(event) => event.stopPropagation()}
      >
        <div data-testid="ai-chatbot-panel" className="relative mx-auto flex h-[calc(100dvh-3rem)] w-full max-w-[1064px] flex-col">
          {hasMessages ? (
            <div className="flex h-full min-h-0 items-center justify-center">
              <div className="relative flex max-h-full min-h-0 w-full max-w-[896px] flex-col">
                {centeredCloseButton}
                <div className="relative min-h-0">
                  <div
                    ref={centerMessagesScrollRef}
                    data-testid="ai-chat-messages"
                    className="scrollbar-hidden max-h-[calc(100dvh-14rem)] space-y-6 overflow-y-auto pb-4 pt-3"
                    onWheel={(event) => {
                      event.stopPropagation();
                      revealCenterScrollbar();
                    }}
                    onScroll={revealCenterScrollbar}
                    onTouchMove={(event) => {
                      event.stopPropagation();
                      revealCenterScrollbar();
                    }}
                  >
                    {centeredMessages.map((message) => (
                      <ChatMessage
                        key={message.id}
                        message={message}
                        onSelectBuild={selectBuild}
                        onQuickReply={handleQuickReply}
                        runningQuickReplyCommandId={runningQuickReplyCommandId}
                        applyingBuildId={applyingBuildId}
                        size="large"
                      />
                    ))}
                    {isSending ? (
                      <div className="rounded-[22px] bg-slate-50 px-5 py-4 text-[20px] font-bold leading-8 text-slate-500">
                        서버에서 추천 조합을 계산하는 중입니다.
                      </div>
                    ) : null}
                    <div ref={messagesEndRef} />
                  </div>

                  {centerScrollbar.canScroll ? (
                    <div
                      aria-hidden="true"
                      data-testid="ai-chat-custom-scrollbar"
                      className="pointer-events-none absolute -right-7 w-[11px] rounded-full bg-white/10"
                      style={{
                        bottom: CENTER_SCROLLBAR_TRACK_BOTTOM,
                        opacity: centerScrollbar.visible ? 1 : 0,
                        top: CENTER_SCROLLBAR_TRACK_TOP,
                        transition: centerScrollbar.visible ? 'opacity 120ms ease-out' : 'opacity 700ms linear',
                        willChange: 'opacity'
                      }}
                    >
                      <div
                        data-testid="ai-chat-custom-scrollbar-thumb"
                        className="w-full rounded-full bg-white/70 shadow-[0_0_10px_rgba(255,255,255,0.18)]"
                        style={{
                          height: centerScrollbar.thumbHeight,
                          transform: `translateY(${centerScrollbar.thumbTop}px)`
                        }}
                      />
                    </div>
                  ) : null}
                </div>
                <div className="mt-3 shrink-0">
                  {centeredPromptForm}
                </div>
              </div>
            </div>
          ) : (
            <div className="flex h-full items-center justify-center px-0 py-12 text-center">
              <div className="relative mx-auto flex w-full max-w-[896px] -translate-y-3 flex-col items-center">
                {centeredCloseButton}
                <h2 className="text-[34px] font-black leading-[42px] text-white">
                  어떤 PC를 맞춰볼까요?
                </h2>
                <p className="mt-3 max-w-[728px] break-keep text-[20px] font-semibold leading-8 text-white/75">
                  예산, 주로 하는 게임이나 작업, 선호 브랜드를 편하게 입력해 주세요.
                </p>
                <div className="mt-7 w-full">
                  {centeredPromptForm}
                </div>
              </div>
            </div>
          )}
        </div>
      </section>
    );
  }

  const panelClassName = isEmbedded
    ? 'panel flex h-full min-h-0 flex-col overflow-hidden bg-[#f8fbff]'
    : isDesktopAssistant
    ? 'fixed inset-y-0 right-0 z-50 flex h-dvh w-[420px] flex-col overflow-hidden border-l border-slate-200 bg-[#f8fbff] shadow-2xl'
    : 'fixed bottom-4 right-3 z-50 w-[min(calc(100vw-1.5rem),460px)] overflow-hidden rounded-2xl border border-slate-200 bg-[#f8fbff] shadow-2xl';
  const bodyClassName = `${isEmbedded || isDesktopAssistant ? 'min-h-0 flex-1' : 'max-h-[78vh]'} flex flex-col`;

  const panel = (
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
          {!isEmbedded ? (
            <button
              type="button"
              aria-label="AI 견적 챗봇 닫기"
              onClick={() => setOpen(false)}
              className="grid h-9 w-9 place-items-center rounded-md border border-slate-200 bg-white text-slate-500 transition hover:border-slate-300 hover:text-commerce-ink focus:outline-none focus:ring-4 focus:ring-blue-100"
            >
              <X size={17} />
            </button>
          ) : null}
        </div>
      </div>

      <div className={bodyClassName}>
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
              onQuickReply={handleQuickReply}
              runningQuickReplyCommandId={runningQuickReplyCommandId}
              applyingBuildId={applyingBuildId}
            />
          ))}
          {isSending ? (
            <div className="rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm font-bold text-slate-500 shadow-sm">
              서버에서 추천 조합을 계산하는 중입니다.
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
            추천은 서버의 부품 데이터 기준으로 계산되며 대화 기록은 브라우저에만 임시 저장됩니다(창을 닫으면 사라집니다).
          </div>
        </form>
      </div>
    </section>
  );

  return panel;
}

function normalizeBoardFocus(value: AiBoardFocus | null | undefined): AiBoardFocus | null {
  if (!value || value.type !== 'PART_LOCATION') return null;
  const categories = value.categories.filter((category): category is PartCategory => category in PART_CATEGORY_LABELS);
  const uniqueCategories = [...new Set(categories)];
  if (uniqueCategories.length === 0) return null;
  return {
    type: 'PART_LOCATION',
    categories: uniqueCategories,
    label: value.label || `${uniqueCategories.map((category) => PART_CATEGORY_LABELS[category]).join(' · ')} 위치`
  };
}

// 서버가 현재 견적(드래프트) 문맥을 실제로 쓰는 요청만 draft를 먼저 확보한다.
// 백엔드 BuildChatIntentRouter의 시뮬레이션/견적완성 판정 어휘와 맞춘다.
function needsDraftContext(prompt: string) {
  const normalized = prompt.toLowerCase().replace(/\s+/g, '');
  const completionLike = /지금|현재|이견적|그래프|나머지|마저|채워|완성/.test(normalized);
  const simulationLike = /바꾸면|바꿨|교체하면|교체시|넣으면|달면|끼우면|끼면|박으면|올리면|올렸|내리면|내려|낮추면|늘리면|줄이면|갈아|넘어가면|업그레이드하면|다운그레이드하면|프레임|fps|성능|체감|비교/.test(normalized);
  // 변경 명령("그래픽카드 더 싼걸로", "램 빼줘")은 서버가 드래프트 기준 미리보기를 만들므로
  // 낡은 캐시본이 아닌 최신 드래프트를 실어 보낸다.
  const modifyLike = /바꿔|교체|싼|저렴|올려|빼|제거|삭제|넣어|담아|추가|수량|늘려|줄여/.test(normalized);
  return completionLike || simulationLike || modifyLike;
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

const ChatMessage = memo(function ChatMessage({
  message,
  onSelectBuild,
  onQuickReply,
  runningQuickReplyCommandId,
  applyingBuildId,
  size = 'default'
}: {
  message: AiChatMessage;
  onSelectBuild: (build: AiRecommendedBuild) => void;
  onQuickReply: (reply: string, command?: AiQuickReplyCommand, messageId?: string) => void;
  runningQuickReplyCommandId: string | null;
  applyingBuildId: string | null;
  size?: AiChatMessageSize;
}) {
  const isUser = message.role === 'user';
  const isLarge = size === 'large';

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-full ${isUser ? 'w-fit max-w-[86%]' : 'w-full'}`}>
        <div className={`${isLarge ? 'rounded-[22px] px-5 py-4 text-[20px] leading-8' : 'rounded-2xl px-3 py-2 text-sm leading-6'} shadow-sm ${isUser ? 'bg-brand-blue text-white' : 'border border-slate-200 bg-white text-slate-700'}`}>
          {!isUser ? (
            <div className={`${isLarge ? 'mb-2 gap-3 text-[15px]' : 'mb-1 gap-2 text-[11px]'} flex items-center font-black text-brand-blue`}>
              <span className={`${isLarge ? 'h-7 w-7' : 'h-5 w-5'} grid place-items-center rounded-full bg-blue-50 text-brand-blue`}>
                <Sparkles size={isLarge ? 17 : 12} />
              </span>
              {message.buildAssessment ? '견적 점수 설명' : message.simulation ? '성능 시뮬레이션' : 'AI 견적 어시스턴트'}
            </div>
          ) : null}
          <p className="break-keep">{message.text}</p>
          {!isUser && message.quickReplies?.length ? (
            <div data-testid="ai-quick-replies" className={`${isLarge ? 'mt-3 gap-3' : 'mt-2 gap-2'} flex flex-wrap`}>
              {message.quickReplies.map((reply) => {
                const command = message.quickReplyCommands?.find((item) => item.label === reply);
                const commandId = command ? `${message.id}:${command.partId}` : null;
                const isRunning = commandId !== null && runningQuickReplyCommandId === commandId;
                return (
                  <button
                    key={reply}
                    type="button"
                    disabled={isRunning}
                    onClick={() => onQuickReply(reply, command, message.id)}
                    className={`${isLarge ? 'px-4 py-2 text-[15px]' : 'px-3 py-1.5 text-[11px]'} rounded-full border border-slate-200 bg-white font-black text-slate-600 shadow-sm transition hover:border-brand-blue hover:text-brand-blue focus:outline-none focus:ring-4 focus:ring-blue-100 disabled:cursor-wait disabled:opacity-60`}
                  >
                    {isRunning ? '추가 중...' : reply}
                  </button>
                );
              })}
            </div>
          ) : null}
        </div>

        {message.simulation ? (
          <SimulationResultCard simulation={message.simulation} size={size} />
        ) : null}

        {message.buildAssessment ? (
          <BuildAssessmentCard assessment={message.buildAssessment} size={size} />
        ) : null}

        {message.builds ? (
          <div className={`${isLarge ? 'mt-4 gap-3' : 'mt-2 gap-2'} grid`}>
            {message.builds.map((build) => (
              <CompactBuildCard key={`${message.id}-${build.id}`} build={build} onSelectBuild={onSelectBuild} applyingBuildId={applyingBuildId} size={size} />
            ))}
          </div>
        ) : null}
      </div>
    </div>
  );
}, (prev, next) => (
  // 세션 저장→syncSession이 메시지 객체를 매번 새로 만들기 때문에 참조 비교로는 memo가 무효다.
  // 메시지는 id당 내용이 불변이므로 id + 콜백 참조로 비교해 기존 메시지 리렌더를 막는다.
  // applyingBuildId가 바뀌면 카드 버튼의 로딩/비활성 상태가 갱신되도록 비교에 포함한다.
  prev.message.id === next.message.id
  && prev.onSelectBuild === next.onSelectBuild
  && prev.onQuickReply === next.onQuickReply
  && prev.runningQuickReplyCommandId === next.runningQuickReplyCommandId
  && prev.applyingBuildId === next.applyingBuildId
  && prev.size === next.size
));

function BuildAssessmentCard({ assessment, size = 'default' }: { assessment: AiBuildAssessment; size?: AiChatMessageSize }) {
  const isLarge = size === 'large';
  return (
    <section
      data-testid="ai-build-assessment"
      className={`${isLarge ? 'mt-4 p-5' : 'mt-2 p-3'} rounded-lg border border-blue-100 bg-blue-50/70`}
      aria-label={`현재 견적 종합 점수 ${assessment.score}점 / ${assessment.maxScore}점`}
    >
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <p className={`${isLarge ? 'text-base' : 'text-xs'} font-black text-brand-blue`}>현재 견적 종합 점수</p>
          <p className={`${isLarge ? 'text-4xl' : 'text-2xl'} mt-1 font-black text-commerce-ink`}>
            {assessment.score}<span className={`${isLarge ? 'text-base' : 'text-xs'} ml-1 text-slate-400`}>/ {assessment.maxScore}</span>
          </p>
        </div>
        <span className="rounded-full border border-blue-200 bg-white px-2 py-1 text-[11px] font-black text-brand-blue">
          {assessment.grade} · {assessment.label}
        </span>
      </div>
      <p className={`${isLarge ? 'text-base leading-7' : 'text-xs leading-5'} mt-3 break-keep font-bold text-slate-700`}>
        {assessment.summary}
      </p>
      {assessment.strengths.length ? <AssessmentList title="강점" items={assessment.strengths} tone="positive" /> : null}
      {assessment.cautions.length ? <AssessmentList title="주의점" items={assessment.cautions} tone="caution" /> : null}
      {assessment.recommendations.length ? (
        <div className="mt-3 border-t border-blue-100 pt-3">
          <p className="text-[11px] font-black text-slate-500">개선 우선순위</p>
          <ol className="mt-1.5 space-y-1.5">
            {assessment.recommendations.map((item) => (
              <li key={`${item.priority}-${item.category}`} className="flex gap-2 rounded bg-white px-2.5 py-2 text-xs leading-5 text-slate-700">
                <span className="grid h-5 w-5 shrink-0 place-items-center rounded-full bg-brand-blue text-[10px] font-black text-white">{item.priority}</span>
                <span><strong>{item.title}</strong><br /><span className="text-slate-500">{item.reason}</span></span>
              </li>
            ))}
          </ol>
        </div>
      ) : null}
    </section>
  );
}

function AssessmentList({ title, items, tone }: {
  title: string;
  items: AiBuildAssessment['strengths'];
  tone: 'positive' | 'caution';
}) {
  return (
    <div className="mt-3">
      <p className="text-[11px] font-black text-slate-500">{title}</p>
      <ul className="mt-1.5 space-y-1.5">
        {items.map((item) => (
          <li
            key={item.code}
            className={`rounded border px-2.5 py-2 text-xs leading-5 ${
              tone === 'positive'
                ? 'border-emerald-100 bg-emerald-50 text-emerald-800'
                : item.severity === 'FAIL'
                  ? 'border-red-100 bg-red-50 text-red-800'
                  : 'border-amber-100 bg-amber-50 text-amber-800'
            }`}
          >
            <strong>{item.title}</strong><br />{item.description}
          </li>
        ))}
      </ul>
    </div>
  );
}

function SimulationResultCard({ simulation, size = 'default' }: { simulation: AiPerformanceSimulation; size?: AiChatMessageSize }) {
  const isLarge = size === 'large';
  const fpsRows = simulation.fpsComparisons ?? [];
  const specRows = simulation.specComparisons ?? [];
  const score = simulation.scoreComparison;
  const maxFps = Math.max(
    1,
    ...fpsRows.flatMap((row) => [row.currentFps ?? 0, row.targetFps ?? 0])
  );

  return (
    <section className={`${isLarge ? 'mt-4 rounded-xl p-5' : 'mt-2 rounded-lg p-3'} border border-blue-100 bg-blue-50`}>
      <div className={`${isLarge ? 'gap-3' : 'gap-2'} flex flex-wrap items-start justify-between`}>
        <div className="min-w-0">
          <div className={`${isLarge ? 'gap-3 text-base' : 'gap-2 text-xs'} flex items-center font-black text-brand-blue`}>
            <BarChart3 size={isLarge ? 20 : 14} />
            성능 시뮬레이션
          </div>
          <div className={`${isLarge ? 'mt-2 text-[20px] leading-8' : 'mt-1 text-sm'} break-keep font-black text-commerce-ink`}>
            {simulation.currentPart.name} → {simulation.targetPart.name}
          </div>
        </div>
        <span className={`${isLarge ? 'px-3 py-1.5 text-[15px]' : 'px-2 py-1 text-[11px]'} rounded bg-white font-black text-slate-600`}>
          {PART_CATEGORY_LABELS[simulation.category]}
        </span>
      </div>

      {score ? (
        <div className={`${isLarge ? 'mt-4 rounded-lg p-4' : 'mt-3 rounded-md p-3'} bg-white`}>
          <div className={`${isLarge ? 'gap-3 text-base' : 'gap-2 text-xs'} flex items-center justify-between`}>
            <span className="font-black text-slate-700">{score.label}</span>
            <span className={`font-black ${score.delta && score.delta > 0 ? 'text-commerce-green' : score.delta && score.delta < 0 ? 'text-commerce-sale' : 'text-slate-500'}`}>
              {formatSigned(score.delta, '점')}
            </span>
          </div>
          <div className={`${isLarge ? 'mt-3 gap-3' : 'mt-2 gap-2'} grid`}>
            <ComparisonBar label="현재" value={score.currentScore} max={100} tone="slate" size={size} />
            <ComparisonBar label="변경 후" value={score.targetScore} max={100} tone="blue" size={size} />
          </div>
        </div>
      ) : null}

      {fpsRows.length ? (
        <div className={`${isLarge ? 'mt-4 rounded-lg' : 'mt-3 rounded-md'} overflow-hidden border border-blue-100 bg-white`}>
          <div className={`${isLarge ? 'gap-3 px-4 py-3 text-[15px]' : 'gap-2 px-3 py-2 text-[11px]'} grid grid-cols-[1.2fr_0.8fr_1.2fr] border-b border-blue-50 font-black text-slate-500`}>
            <span>게임/해상도</span>
            <span className="text-right">FPS 변화</span>
            <span>비교 막대</span>
          </div>
          <div className="divide-y divide-blue-50">
            {fpsRows.map((row) => (
              <div key={`${row.gameTitle}-${row.resolution}-${row.graphicsPreset ?? ''}`} className={`${isLarge ? 'gap-3 px-4 py-3 text-base' : 'gap-2 px-3 py-2 text-xs'} grid grid-cols-[1.2fr_0.8fr_1.2fr]`}>
                <div className="min-w-0">
                  <div className="break-keep font-black text-commerce-ink">{row.gameTitle}</div>
                  <div className={`${isLarge ? 'mt-1 text-[15px]' : 'mt-0.5 text-[11px]'} font-bold text-slate-500`}>{row.resolution}{row.graphicsPreset ? ` · ${row.graphicsPreset}` : ''}</div>
                </div>
                <div className="text-right font-black text-slate-700">
                  <div>{formatFps(row.currentFps)} → {formatFps(row.targetFps)}</div>
                  <div className={`${row.deltaFps && row.deltaFps > 0 ? 'text-commerce-green' : row.deltaFps && row.deltaFps < 0 ? 'text-commerce-sale' : 'text-slate-400'}`}>
                    {formatSigned(row.deltaFps, 'fps')}
                  </div>
                </div>
                <div className="grid content-center gap-1">
                  <MiniBar value={row.currentFps} max={maxFps} className="bg-slate-300" size={size} />
                  <MiniBar value={row.targetFps} max={maxFps} className="bg-brand-blue" size={size} />
                </div>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {specRows.length ? (
        <div className={`${isLarge ? 'mt-4 gap-3' : 'mt-3 gap-2'} grid sm:grid-cols-2`}>
          {specRows.map((row) => (
            <div key={row.label} className={`${isLarge ? 'rounded-lg p-3 text-base' : 'rounded-md p-2 text-xs'} border border-blue-100 bg-white`}>
              <div className="font-black text-commerce-ink">{row.label}</div>
              <div className="mt-1 flex items-center justify-between gap-2 text-slate-600">
                <span className="truncate">{row.currentValue ?? '-'}</span>
                <span className="font-black text-slate-400">→</span>
                <span className="truncate font-black text-brand-blue">{row.targetValue ?? '-'}</span>
              </div>
              {row.deltaText ? <div className={`${isLarge ? 'mt-2 text-[15px]' : 'mt-1 text-[11px]'} font-bold text-commerce-green`}>{row.deltaText}</div> : null}
            </div>
          ))}
        </div>
      ) : null}

      {simulation.warnings?.length ? (
        <div className={`${isLarge ? 'mt-4 rounded-lg px-4 py-3 text-base leading-7' : 'mt-3 rounded-md px-3 py-2 text-xs leading-5'} bg-amber-50 font-bold text-amber-700`}>
          {simulation.warnings[0]}
        </div>
      ) : null}
      <p className={`${isLarge ? 'mt-4 text-[15px] leading-7' : 'mt-3 text-[11px] leading-5'} break-keep font-bold text-slate-500`}>
        {simulation.disclaimer
          ?? '본 수치는 내부 벤치마크 DB 기준 참고용 추정치입니다. 실제 성능은 게임 버전, 그래픽 옵션, 드라이버, 해상도, 냉각·전원 환경에 따라 달라질 수 있습니다.'}
      </p>
    </section>
  );
}

function ComparisonBar({ label, value, max, tone, size = 'default' }: { label: string; value?: number | null; max: number; tone: 'slate' | 'blue'; size?: AiChatMessageSize }) {
  const isLarge = size === 'large';
  return (
    <div className={`${isLarge ? 'grid-cols-[68px_1fr_62px] gap-3 text-[15px]' : 'grid-cols-[48px_1fr_44px] gap-2 text-[11px]'} grid items-center font-bold text-slate-500`}>
      <span>{label}</span>
      <MiniBar value={value} max={max} className={tone === 'blue' ? 'bg-brand-blue' : 'bg-slate-300'} size={size} />
      <span className="text-right text-commerce-ink">{formatPlainNumber(value)}</span>
    </div>
  );
}

function MiniBar({ value, max, className, size = 'default' }: { value?: number | null; max: number; className: string; size?: AiChatMessageSize }) {
  const width = Math.max(4, Math.min(100, Math.round(((value ?? 0) / Math.max(1, max)) * 100)));
  return (
    <div className={`${size === 'large' ? 'h-3' : 'h-2'} overflow-hidden rounded-full bg-slate-100`}>
      <div className={`h-full rounded-full ${className}`} style={{ width: `${width}%` }} />
    </div>
  );
}

// 변경 미리보기 → 성능 패널 비교 연동을 이미 보낸 빌드 — 채팅을 다시 열어 과거 카드가
// 재마운트될 때 옛 비교가 다시 켜지지 않도록 빌드 단위로 1회만 발행한다.
const perfCompareNotifiedBuildIds = new Set<string>();

function CompactBuildCard({
  build,
  onSelectBuild,
  applyingBuildId,
  size = 'default'
}: {
  build: AiRecommendedBuild;
  onSelectBuild: (build: AiRecommendedBuild) => void;
  applyingBuildId: string | null;
  size?: AiChatMessageSize;
}) {
  // 변경 미리보기 카드는 전체 견적이 아니라 바뀐 부품만 보여준다(전체 8부품 나열은 무엇이 바뀌는지 흐린다).
  // 적용은 여전히 build 전체(items 전량)로 하므로 나머지 부품이 삭제되지 않는다.
  const isEditPreview = build.label === '변경 미리보기' && build.appliedPartCategories.length > 0;
  const changedItems = build.items.filter((item) => build.appliedPartCategories.includes(item.category));

  // CPU/GPU 변경 미리보기가 그려지면 셀프견적 성능 패널의 교체 비교도 함께 켠다(이벤트 발행만 — 디자인 불변).
  useEffect(() => {
    if (!isEditPreview || perfCompareNotifiedBuildIds.has(build.id)) return;
    const changed = changedItems.find((item) => (item.category === 'CPU' || item.category === 'GPU') && item.partId);
    if (!changed) return;
    perfCompareNotifiedBuildIds.add(build.id);
    requestPerfCompare({
      category: changed.category as 'CPU' | 'GPU',
      partId: changed.partId,
      name: changed.name,
      price: changed.price
    });
  }, [build.id, isEditPreview, changedItems]);
  const primaryItems = isEditPreview && changedItems.length > 0 ? changedItems : build.items.slice(0, 5);
  const isLarge = size === 'large';
  // 이 카드가 적용 중이면 로딩 표시, 다른 카드가 적용 중이면 클릭이 조용히 무시되지 않도록 함께 비활성화한다.
  const isApplyingThis = applyingBuildId === build.id;
  const isApplyDisabled = Boolean(applyingBuildId);

  return (
    <article className={`${isLarge ? 'rounded-[22px] p-5' : 'rounded-2xl p-3'} border border-slate-200 bg-white shadow-sm`}>
      <div className={`${isLarge ? 'gap-3' : 'gap-2'} flex flex-wrap items-center`}>
        <span className={`${isLarge ? 'px-3 py-1.5 text-[15px]' : 'px-2.5 py-1 text-[11px]'} rounded-full bg-brand-blue font-black text-white`}>{build.label}</span>
        {build.appliedPartCategories.map((category) => (
          <span key={category} className={`${isLarge ? 'px-3 py-1.5 text-[15px]' : 'px-2.5 py-1 text-[11px]'} rounded-full bg-emerald-50 font-black text-emerald-700`}>
            {PART_CATEGORY_LABELS[category]} 반영됨
          </span>
        ))}
      </div>
      <div className={`${isLarge ? 'mt-4 gap-3' : 'mt-3 gap-2'} flex flex-col sm:flex-row sm:items-start sm:justify-between`}>
        <div className="min-w-0">
          <h3 className={`${isLarge ? 'text-[20px] leading-8' : 'text-sm leading-5'} font-black text-commerce-ink`}>{build.title}</h3>
          <p className={`${isLarge ? 'mt-2 text-base leading-7' : 'mt-1 text-xs leading-5'} line-clamp-2 break-keep text-slate-500`}>{build.summary}</p>
        </div>
        <div className="shrink-0 text-left sm:text-right">
          <div className={`${isLarge ? 'text-[22px] leading-8' : 'text-base'} font-black text-brand-blue`}>{build.totalPrice.toLocaleString()}원</div>
          <div className={`${isLarge ? 'text-[15px]' : 'text-[11px]'} font-bold text-slate-500`}>
            {isEditPreview ? `변경 ${primaryItems.length}건` : `${build.items.length}개 부품`}
          </div>
        </div>
      </div>
      {build.toolResults?.length ? (
        <div className={`${isLarge ? 'mt-4 gap-2' : 'mt-3 gap-1.5'} flex flex-wrap`} aria-label="검증 결과">
          {build.toolResults.map((result) => (
            <span
              key={`${result.tool}-${result.status}`}
              title={result.summary}
              className={`${isLarge ? 'px-3 py-1.5 text-[15px]' : 'px-2 py-1 text-[11px]'} rounded-full border font-black ${toolStatusChipClass(result.status)}`}
            >
              {toolDisplayLabel(result.tool)} {toolStatusLabel(result.status)}
            </span>
          ))}
        </div>
      ) : null}
      <div className={`${isLarge ? 'mt-4 gap-2 text-base' : 'mt-3 gap-1.5 text-xs'} grid`}>
        {primaryItems.map((item) => (
          <div key={item.partId} className={`${isLarge ? 'grid-cols-[78px_minmax(0,1fr)] gap-3 rounded-xl px-4 py-2.5' : 'grid-cols-[56px_minmax(0,1fr)] gap-2 rounded-lg px-2.5 py-1.5'} grid bg-slate-50`}>
            <span className="font-black text-brand-blue">{PART_CATEGORY_LABELS[item.category]}</span>
            <span className="truncate font-semibold text-slate-700">{item.name}</span>
          </div>
        ))}
      </div>
      <button
        type="button"
        onClick={() => onSelectBuild(build)}
        disabled={isApplyDisabled}
        className={`${isLarge ? 'mt-4 min-h-14 gap-3 px-4 text-base' : 'mt-3 min-h-10 gap-2 px-3 text-xs'} flex w-full items-center justify-center rounded-full border border-blue-200 bg-white font-black text-brand-blue transition hover:border-brand-blue hover:bg-blue-50 focus:outline-none focus:ring-4 focus:ring-blue-100 disabled:border-slate-200 disabled:bg-slate-50 disabled:text-slate-400`}
      >
        <ShoppingCart size={isLarge ? 21 : 15} />
        {isApplyingThis ? '셀프 견적에 적용 중...' : '이 조합으로 셀프 견적 보기'}
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
  if (status === 'PASS') return '통과';
  if (status === 'WARN') return '주의';
  return '불가';
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
