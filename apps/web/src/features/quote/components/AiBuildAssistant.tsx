import { type FormEvent, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Bot, CheckCircle2, Cpu, PackageCheck, Send, ShoppingCart, Sparkles, X, Zap } from 'lucide-react';
import { AUTH_CHANGED_EVENT, ApiError, clearToken, getToken } from '../../../lib/api';
import { applyAiBuildToQuoteDraft, deleteQuoteDraftItem, getCurrentQuoteDraft, patchQuoteDraftItem, putQuoteDraftItem } from '../../parts/partsApi';
import {
  AI_ASSISTANT_SESSION_CHANGED_EVENT,
  PART_CATEGORY_LABELS,
  clearLegacyAiStorage,
  createAiMessageId,
  getAiStorageOwnerKey,
  normalizeAiBuilds,
  normalizeAiRecommendedBuild,
  readAssistantSession,
  saveAssistantSession,
  saveSelectedAiBuild,
  type AiAppliedPartPreference,
  type AiBuildItem,
  type AiChatMessage,
  type AiDraftAction,
  type AiDraftActionStatus,
  type AiRecommendedBuild,
  type BuildGraphFocus,
  type PartCategory
} from '../aiSelection';
import { buildChat } from '../quoteApi';

type AiBuildAssistantProps = {
  surface?: 'home' | 'self-quote';
};

const LOGIN_REQUIRED_MESSAGE = '로그인이 필요합니다. 다시 로그인해 주세요.';
const GENERIC_SUBMIT_ERROR_MESSAGE = 'AI 추천 API 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.';

export function AiBuildAssistant({ surface = 'home' }: AiBuildAssistantProps) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const [open, setOpen] = useState(false);
  const [prompt, setPrompt] = useState('');
  const [session, setSession] = useState(() => readAssistantSession());
  const [isSending, setIsSending] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [applyError, setApplyError] = useState<string | null>(null);
  const [failedBuild, setFailedBuild] = useState<AiRecommendedBuild | null>(null);
  const [applyingBuildId, setApplyingBuildId] = useState<string | null>(null);
  const [applyingActionId, setApplyingActionId] = useState<string | null>(null);
  const hasToken = Boolean(getToken());
  const quoteDraftQuery = useQuery({
    queryKey: ['quote-draft', 'current'],
    queryFn: getCurrentQuoteDraft,
    enabled: surface === 'self-quote' && hasToken
  });

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

  async function submitPrompt(event: FormEvent) {
    event.preventDefault();
    const nextPrompt = prompt.trim();
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

    if (isApplyLatestBuildIntent(nextPrompt)) {
      const latestBuild = baseSession.latestBuilds[0];
      const responseTime = new Date().toISOString();
      const assistantMessage: AiChatMessage = {
        id: createAiMessageId('route'),
        role: 'assistant',
        text: latestBuild
          ? '최근 AI 추천 조합을 셀프 견적에 바로 적용합니다.'
          : '먼저 AI 추천 견적을 만든 뒤 다시 요청해 주세요.',
        createdAt: responseTime,
        kind: 'general'
      };
      const nextSession = {
        ...optimisticSession,
        messages: [...optimisticSession.messages, assistantMessage],
        updatedAt: responseTime
      };
      setSession(nextSession);
      saveAssistantSession(nextSession, ownerKey);
      if (latestBuild) {
        void selectBuild(latestBuild);
      }
      return;
    }

    const routeIntent = fastRouteIntent(nextPrompt);
    if (routeIntent) {
      const responseTime = new Date().toISOString();
      const latestGraphFocus: BuildGraphFocus | undefined = routeIntent.category
        ? { mode: 'PART_IMPACT', category: routeIntent.category }
        : baseSession.latestGraphFocus;
      const assistantMessage: AiChatMessage = {
        id: createAiMessageId('route'),
        role: 'assistant',
        text: routeIntent.message,
        createdAt: responseTime,
        kind: 'general'
      };
      const nextSession = {
        ...optimisticSession,
        messages: [...optimisticSession.messages, assistantMessage],
        latestGraphFocus,
        updatedAt: responseTime
      };
      setSession(nextSession);
      saveAssistantSession(nextSession, ownerKey);
      navigate(routeIntent.route);
      return;
    }

    setIsSending(true);

    try {
      const response = await buildChat({
        message: nextPrompt,
        currentBuilds: baseSession.latestBuilds,
        appliedPartPreferences: baseSession.appliedPartPreferences,
        currentQuoteDraft: surface === 'self-quote' ? quoteDraftQuery.data : undefined
      });
      const responseTime = new Date().toISOString();
      const responseBuilds = response.builds?.length ? normalizeAiBuilds(response.builds) : undefined;
      const latestBuilds = responseBuilds ?? baseSession.latestBuilds;
      const latestGraphFocus = graphFocusFromResponse(response, nextPrompt);
      const appliedPartPreferences = response.partRecommendation
        ? replaceAppliedPartPreference(baseSession.appliedPartPreferences, {
            category: response.partRecommendation.category,
            label: response.partRecommendation.label,
            appliedAt: responseTime,
            options: response.partRecommendation.options
          })
        : baseSession.appliedPartPreferences;
      const assistantMessage: AiChatMessage = {
        id: createAiMessageId(response.answerType.toLowerCase()),
        role: 'assistant',
        text: response.message,
        createdAt: responseTime,
        kind: messageKind(response.answerType),
        builds: responseBuilds,
        partRecommendation: response.partRecommendation ?? undefined,
        actions: response.actions?.length ? response.actions.map((action) => ({ ...action, status: 'PENDING' })) : undefined,
        warnings: response.warnings ?? []
      };
      const nextSession = {
        messages: [...optimisticSession.messages, assistantMessage],
        latestBuilds,
        appliedPartPreferences,
        latestGraphFocus,
        latestActiveBuildId: latestBuilds[1]?.id ?? latestBuilds[0]?.id,
        updatedAt: responseTime
      };
      setSession(nextSession);
      saveAssistantSession(nextSession, ownerKey);
      if (assistantMessage.actions?.length) {
        void autoExecuteActions(assistantMessage.actions, assistantMessage.id);
      }
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

  async function applyDraftAction(action: AiDraftAction, messageId: string) {
    if (applyingActionId || action.status === 'APPLIED') return;
    await executeDraftAction(action, messageId);
  }

  async function autoExecuteActions(actions: AiDraftAction[], messageId: string) {
    for (const action of primaryAutoExecutableActions(actions)) {
      if (!shouldAutoExecuteAction(action)) continue;
      await executeDraftAction(action, messageId, true);
    }
  }

  function primaryAutoExecutableActions(actions: AiDraftAction[]) {
    const result: AiDraftAction[] = [];
    const seenDraftCategories = new Set<string>();
    for (const action of actions) {
      if (!shouldAutoExecuteAction(action)) continue;
      if (action.type === 'OPEN_ROUTE' || action.type === 'ADD_BUILD_TO_DRAFT') {
        result.push(action);
        continue;
      }
      const category = typeof action.payload.category === 'string' ? action.payload.category : action.id;
      if (seenDraftCategories.has(category)) continue;
      seenDraftCategories.add(category);
      result.push(action);
    }
    return result;
  }

  async function executeDraftAction(action: AiDraftAction, messageId: string, automatic = false) {
    if (!automatic && applyingActionId) return;
    if (action.status === 'APPLIED') return;
    setApplyError(null);
    setApplyingActionId(action.id);
    markDraftActionStatus(messageId, action.id, 'APPLYING');
    try {
      if (action.type === 'OPEN_ROUTE') {
        const route = typeof action.payload.route === 'string' ? action.payload.route : null;
        if (!route || !isAllowedUserRoute(route)) {
          throw new Error('route is not allowed');
        }
        navigate(route);
        markDraftActionStatus(messageId, action.id, 'APPLIED');
        return;
      }
      const partId = typeof action.payload.partId === 'string' ? action.payload.partId : null;
      const quantity = typeof action.payload.quantity === 'number' ? action.payload.quantity : 1;
      if (action.type === 'ASK_FOLLOW_UP') {
        markDraftActionStatus(messageId, action.id, 'APPLIED');
        return;
      }
      if (action.type === 'ADD_BUILD_TO_DRAFT') {
        const buildId = typeof action.payload.buildId === 'string' ? action.payload.buildId : null;
        const items = Array.isArray(action.payload.items) ? action.payload.items : [];
        if (!buildId) {
          throw new Error('buildId is required for build draft action');
        }
        await applyAiBuildToQuoteDraft({
          buildId,
          conflictPolicy: 'REPLACE',
          items: items
            .filter((item): item is { partId: string; category: PartCategory; quantity?: number } => (
              typeof item === 'object'
              && item !== null
              && typeof (item as { partId?: unknown }).partId === 'string'
              && isPartCategory((item as { category?: unknown }).category)
            ))
            .map((item) => ({
              partId: item.partId,
              category: item.category,
              quantity: typeof item.quantity === 'number' ? item.quantity : 1
            }))
        });
        await queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
        markDraftActionStatus(messageId, action.id, 'APPLIED');
        navigate('/self-quote');
        return;
      }
      if (!partId) {
        throw new Error('partId is required for draft action');
      }
      if (action.type === 'REMOVE_DRAFT_PART') {
        await deleteQuoteDraftItem(partId);
      } else if (action.type === 'UPDATE_DRAFT_QUANTITY') {
        await patchQuoteDraftItem(partId, quantity);
      } else {
        await putQuoteDraftItem(partId, quantity);
      }
      await queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
      markDraftActionStatus(messageId, action.id, 'APPLIED');
    } catch {
      markDraftActionStatus(messageId, action.id, 'FAILED');
      setApplyError('AI 변경안을 견적 장바구니에 적용하지 못했습니다.');
    } finally {
      setApplyingActionId(null);
    }
  }

  function markDraftActionStatus(messageId: string, actionId: string, status: AiDraftActionStatus) {
    setSession((current) => {
      const nextSession = {
        ...current,
        messages: current.messages.map((message) => {
          if (message.id !== messageId || !message.actions) return message;
          return {
            ...message,
            actions: message.actions.map((action) => action.id === actionId ? { ...action, status } : action)
          };
        }),
        updatedAt: new Date().toISOString()
      };
      saveAssistantSession(nextSession);
      return nextSession;
    });
  }

  if (!open) {
    return (
      <button
        type="button"
        aria-label="AI 견적 챗봇 열기"
        data-testid="ai-chatbot-launcher"
        onClick={() => setOpen(true)}
        className="fixed bottom-5 right-5 z-50 flex h-16 w-16 items-center justify-center rounded-2xl border border-slate-900 bg-slate-950 text-white shadow-2xl transition hover:-translate-y-0.5 hover:bg-slate-800 focus:outline-none focus:ring-4 focus:ring-blue-200"
      >
        <span className="relative grid h-11 w-11 place-items-center rounded-xl bg-white text-slate-950">
          <Bot size={26} />
          <span className="absolute -right-1 -top-1 h-3 w-3 rounded-full border-2 border-slate-950 bg-emerald-400" />
        </span>
      </button>
    );
  }

  return (
    <section
      data-testid="ai-chatbot-panel"
      className="fixed bottom-4 right-3 z-50 w-[min(calc(100vw-1.5rem),460px)] overflow-hidden rounded-xl border border-slate-900 bg-white shadow-2xl sm:bottom-5 sm:right-4"
    >
      <div className="bg-slate-950 px-4 py-3 text-white">
        <div className="flex items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-3">
            <div className="grid h-10 w-10 shrink-0 place-items-center rounded-lg bg-white text-slate-950">
              <Bot size={22} />
            </div>
            <div className="min-w-0">
              <h2 className="truncate text-sm font-black">BuildGraph AI 챗봇</h2>
              <p className="truncate text-xs text-white/70">{surface === 'home' ? '예산/부품 DB 추천' : '셀프견적 보조 추천'}</p>
            </div>
          </div>
          <button
            type="button"
            aria-label="AI 견적 챗봇 닫기"
            onClick={() => setOpen(false)}
            className="grid h-9 w-9 place-items-center rounded-md border border-white/15 text-white/80 hover:bg-white/10 hover:text-white"
          >
            <X size={17} />
          </button>
        </div>
      </div>

      <div className="flex max-h-[78vh] flex-col">
        <div className="border-b border-commerce-line bg-slate-50 px-4 py-3">
          <div className="flex flex-wrap gap-2">
            {['200만원 PC 추천', '300만원 PC 추천', 'GPU 추천해줘', '쿨러 추천해줘'].map((example) => (
              <button
                key={example}
                type="button"
                onClick={() => setPrompt(example)}
                className="rounded-full border border-commerce-line bg-white px-3 py-1 text-[11px] font-black text-slate-600 hover:border-commerce-ink hover:text-commerce-ink"
              >
                {example}
              </button>
            ))}
          </div>
        </div>

        <div data-testid="ai-chat-messages" className="min-h-0 flex-1 space-y-3 overflow-y-auto p-4">
          {session.messages.map((message) => (
            <ChatMessage
              key={message.id}
              message={message}
              onSelectBuild={selectBuild}
              onApplyDraftAction={applyDraftAction}
              applyingActionId={applyingActionId}
            />
          ))}
          {isSending ? (
            <div className="rounded-xl border border-commerce-line bg-white px-3 py-2 text-sm font-bold text-slate-500">
              서버 DB에서 추천 조합을 계산하는 중입니다.
            </div>
          ) : null}
          <div ref={messagesEndRef} />
        </div>

        <form onSubmit={submitPrompt} className="border-t border-commerce-line bg-white p-3">
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
          <div className="flex gap-2 rounded-lg border border-commerce-line bg-slate-50 p-2 focus-within:border-commerce-ink focus-within:ring-4 focus-within:ring-blue-100">
            <input
              id="ai-build-chat-input"
              aria-label="AI 챗봇에게 PC 사양 질문"
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              disabled={isSending}
              placeholder="예: 200만원 PC 추천, GPU 추천해줘"
              className="min-w-0 flex-1 bg-transparent px-2 text-sm font-medium text-slate-900 outline-none placeholder:text-slate-400"
            />
            <button
              type="submit"
              aria-label="질문 보내기"
              disabled={!prompt.trim() || isSending}
              className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-commerce-ink text-white transition hover:bg-slate-700 disabled:bg-slate-300"
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

function messageKind(answerType: 'BUDGET' | 'PART' | 'GENERAL'): AiChatMessage['kind'] {
  if (answerType === 'BUDGET') return 'budget';
  if (answerType === 'PART') return 'part';
  return 'general';
}

function replaceAppliedPartPreference(preferences: AiAppliedPartPreference[], nextPreference: AiAppliedPartPreference) {
  return [
    ...preferences.filter((preference) => preference.category !== nextPreference.category),
    nextPreference
  ];
}

function graphFocusFromResponse(
  response: {
    answerType: 'BUDGET' | 'PART' | 'GENERAL';
    partRecommendation?: { category?: BuildGraphFocus['category'] } | null;
    actions?: AiDraftAction[];
    warnings?: string[];
  },
  prompt: string
): BuildGraphFocus {
  if (response.actions?.length) {
    const actionCategory = response.actions.find((action) => typeof action.payload.category === 'string')?.payload.category;
    return {
      mode: 'DRAFT_ACTION',
      category: actionCategory,
      tool: toolFromPrompt(prompt)
    };
  }
  if (response.answerType === 'PART' && response.partRecommendation?.category) {
    return {
      mode: 'PART_IMPACT',
      category: response.partRecommendation.category,
      tool: toolFromCategory(response.partRecommendation.category)
    };
  }
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

function toolFromCategory(category?: BuildGraphFocus['category']): BuildGraphFocus['tool'] {
  if (category === 'GPU' || category === 'PSU') return 'power';
  if (category === 'CASE' || category === 'COOLER') return 'size';
  if (category === 'CPU' || category === 'MOTHERBOARD' || category === 'RAM') return 'compatibility';
  return undefined;
}

function toolFromPrompt(prompt: string): BuildGraphFocus['tool'] {
  if (/파워|전력|w\b|W\b|psu/i.test(prompt)) return 'power';
  if (/케이스|크기|장착|길이|쿨러|높이/.test(prompt)) return 'size';
  if (/호환|소켓|메인보드|보드|ram|DDR/i.test(prompt)) return 'compatibility';
  if (/성능|게임|QHD|FPS|개발|AI|CUDA/i.test(prompt)) return 'performance';
  if (/가격|예산|만원|원/.test(prompt)) return 'price';
  return undefined;
}

type FastRouteIntent = {
  route: string;
  message: string;
  category?: PartCategory;
};

const PART_CATEGORIES: PartCategory[] = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER'];

function fastRouteIntent(prompt: string): FastRouteIntent | null {
  const normalized = normalizePrompt(prompt);
  const category = routeCategoryFromPrompt(normalized);
  if (category && hasRouteVerb(normalized) && !isProductDetailIntent(normalized)) {
    return {
      route: `/self-quote?category=${category}`,
      message: `${PART_CATEGORY_LABELS[category]} 부품 화면으로 이동했습니다.`,
      category
    };
  }
  if (containsAnyNormalized(normalized, ['셀프견적', '수동견적', '견적장바구니', '장바구니'])
    && !isDraftMutationCommand(normalized)) {
    return { route: '/self-quote', message: '셀프 견적 화면으로 이동했습니다.' };
  }
  if (containsAnyNormalized(normalized, ['내견적함', '견적함', '저장된견적', '견적목록'])) {
    return { route: '/my/quotes', message: '내 견적함 화면으로 이동했습니다.' };
  }
  if (containsAnyNormalized(normalized, ['ai견적', '자연어견적', '요구사항', '견적입력'])) {
    return { route: '/requirements/new', message: 'AI 견적 입력 화면으로 이동했습니다.' };
  }
  if (containsAnyNormalized(normalized, ['as접수', '수리접수', '지원접수', '고장접수'])) {
    return { route: '/support/new', message: 'AS 접수 화면으로 이동했습니다.' };
  }
  if (containsAnyNormalized(normalized, ['as챗봇', 'asai', '수리챗봇', '지원챗봇'])) {
    return { route: '/support/ai-chat', message: 'AS AI 챗봇 화면으로 이동했습니다.' };
  }
  if (containsAnyNormalized(normalized, ['구매하기', '결제', 'checkout'])) {
    return { route: '/checkout', message: '구매하기 화면으로 이동했습니다.' };
  }
  return null;
}

function isApplyLatestBuildIntent(prompt: string) {
  const normalized = normalizePrompt(prompt);
  return containsAnyNormalized(normalized, ['담아줘', '적용해줘', '넣어줘', '장바구니에담'])
    && containsAnyNormalized(normalized, ['추천견적', '추천조합', '이견적', '이조합', '견적']);
}

function shouldAutoExecuteAction(action: AiDraftAction) {
  return action.type !== 'ASK_FOLLOW_UP';
}

function isAllowedUserRoute(route: string) {
  if (route === '/self-quote'
    || route === '/my/quotes'
    || route === '/requirements/new'
    || route === '/support/new'
    || route === '/support/ai-chat'
    || route === '/checkout') {
    return true;
  }
  if (/^\/self-quote\?category=(CPU|MOTHERBOARD|RAM|GPU|STORAGE|PSU|CASE|COOLER)$/.test(route)) {
    return true;
  }
  return /^\/parts\/[0-9a-fA-F-]{8,}$/.test(route);
}

function isPartCategory(value: unknown): value is PartCategory {
  return typeof value === 'string' && PART_CATEGORIES.includes(value as PartCategory);
}

function routeCategoryFromPrompt(normalized: string): PartCategory | null {
  const checks: Array<[PartCategory, string[]]> = [
    ['MOTHERBOARD', ['메인보드', '마더보드', '보드', 'motherboard']],
    ['COOLER', ['쿨러', 'cooler', '수랭', '공랭']],
    ['STORAGE', ['ssd', '스토리지', '저장장치', '저장공간', 'nvme']],
    ['PSU', ['파워', 'psu', '전원']],
    ['CASE', ['케이스', 'case']],
    ['GPU', ['gpu', '그래픽카드', '그래픽', '글카', 'vga']],
    ['CPU', ['cpu', '씨피유', '프로세서']],
    ['RAM', ['ram', '램', '메모리']]
  ];
  return checks.find(([, keywords]) => keywords.some((keyword) => normalized.includes(normalizePrompt(keyword))))?.[0] ?? null;
}

function hasRouteVerb(normalized: string) {
  const routeLike = containsAnyNormalized(normalized, ['보여', '열어', '이동', '가자', '목록', '페이지', '카테고리', '부품', '화면']);
  const recommendationOnly = normalized.includes('추천') && !containsAnyNormalized(normalized, ['보여', '열어', '목록', '페이지', '부품']);
  return routeLike && !recommendationOnly;
}

function isDraftMutationCommand(normalized: string) {
  return containsAnyNormalized(normalized, ['담아', '넣어', '적용', '추가', '빼', '삭제', '제거', '바꿔', '교체', '수량', '변경']);
}

function isProductDetailIntent(normalized: string) {
  const detailWord = containsAnyNormalized(normalized, ['상세', '상품페이지', '제품페이지', '제품상세', '상품상세']);
  const concreteProductHint = /\d{3,5}/.test(normalized)
    || containsAnyNormalized(normalized, ['asus', 'msi', 'gigabyte', 'lianli', '리안리', 'samsung', '삼성', 'corsair', '커세어', 'noctua', '녹투아', 'arctic']);
  return detailWord && concreteProductHint;
}

function containsAnyNormalized(value: string, needles: string[]) {
  return needles.some((needle) => value.includes(normalizePrompt(needle)));
}

function normalizePrompt(value: string) {
  return value.toLowerCase().replace(/\s+/g, '');
}

function ChatMessage({
  message,
  onSelectBuild,
  onApplyDraftAction,
  applyingActionId
}: {
  message: AiChatMessage;
  onSelectBuild: (build: AiRecommendedBuild) => void;
  onApplyDraftAction: (action: AiDraftAction, messageId: string) => void;
  applyingActionId: string | null;
}) {
  const isUser = message.role === 'user';

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-full ${isUser ? 'w-fit max-w-[86%]' : 'w-full'}`}>
        <div className={`rounded-xl px-3 py-2 text-sm leading-6 ${isUser ? 'bg-commerce-ink text-white' : 'border border-commerce-line bg-white text-slate-700'}`}>
          {!isUser ? (
            <div className="mb-1 flex items-center gap-2 text-[11px] font-black text-brand-blue">
              <Sparkles size={13} />
              AI DB 답변
            </div>
          ) : null}
          <p className="break-keep">{message.text}</p>
        </div>

        {message.builds ? (
          <div className="mt-2 grid gap-2">
            {message.builds.map((build) => (
              <CompactBuildCard key={`${message.id}-${build.id}`} build={build} onSelectBuild={onSelectBuild} />
            ))}
          </div>
        ) : null}

        {message.partRecommendation ? (
          <PartRecommendationCards options={message.partRecommendation.options} label={message.partRecommendation.label} />
        ) : null}

        {message.actions?.length ? (
          <DraftActionCards
            messageId={message.id}
            actions={message.actions}
            applyingActionId={applyingActionId}
            onApplyDraftAction={onApplyDraftAction}
          />
        ) : null}
      </div>
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
  return (
    <article className="rounded-lg border border-commerce-line bg-slate-50 p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="rounded bg-commerce-ink px-2 py-1 text-[11px] font-black text-white">{build.label}</span>
        {build.appliedPartCategories.map((category) => (
          <span key={category} className="rounded bg-blue-50 px-2 py-1 text-[11px] font-black text-brand-blue">
            {PART_CATEGORY_LABELS[category]} 반영됨
          </span>
        ))}
      </div>
      <div className="mt-2 flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h3 className="text-sm font-black text-commerce-ink">{build.title}</h3>
          <p className="mt-1 line-clamp-2 break-keep text-xs leading-5 text-slate-500">{build.summary}</p>
        </div>
        <div className="shrink-0 text-left sm:text-right">
          <div className="text-base font-black text-commerce-sale">{build.totalPrice.toLocaleString()}원</div>
          <div className="text-[11px] font-bold text-commerce-green">8개 부품</div>
        </div>
      </div>
      <div className="mt-3 grid gap-2 text-xs sm:grid-cols-2">
        {build.items.slice(0, 4).map((item) => (
          <div key={item.partId} className="min-w-0 rounded-md bg-white px-2 py-1.5">
            <span className="font-black text-slate-800">{PART_CATEGORY_LABELS[item.category]}</span>
            <span className="ml-1 text-slate-500">{item.name}</span>
          </div>
        ))}
      </div>
      <button
        type="button"
        onClick={() => onSelectBuild(build)}
        className="mt-3 flex w-full min-h-10 items-center justify-center gap-2 rounded-md bg-commerce-ink px-3 text-xs font-black text-white transition hover:bg-slate-700 focus:outline-none focus:ring-4 focus:ring-blue-100"
      >
        <ShoppingCart size={15} />
        이 조합으로 셀프 견적 보기
      </button>
    </article>
  );
}

function PartRecommendationCards({ options, label }: { options: AiBuildItem[]; label: string }) {
  return (
    <div className="mt-2 rounded-lg border border-blue-100 bg-blue-50 p-3">
      <div className="mb-2 flex items-center gap-2 text-xs font-black text-brand-blue">
        <PackageCheck size={14} />
        {label} 추천 후보
      </div>
      <div className="grid gap-2">
        {options.map((option, index) => (
          <div key={option.partId} className="rounded-lg border border-commerce-line bg-white p-3">
            <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
              <span className="rounded bg-slate-100 px-2 py-1 text-[11px] font-black text-slate-700">
                {index === 0 ? '가성비' : index === 1 ? '균형' : '고성능'}
              </span>
              <span className="text-xs font-black text-commerce-sale">{option.price.toLocaleString()}원</span>
            </div>
            <div className="font-black text-commerce-ink">{option.name}</div>
            <div className="mt-1 text-xs text-slate-500">{option.manufacturer} · {option.note}</div>
          </div>
        ))}
      </div>
      <div className="mt-2 flex items-center gap-2 text-[11px] font-bold text-slate-500">
        <Cpu size={13} />
        최신 AI 추천상품 3개에 바로 반영됨
        <Zap size={13} />
      </div>
    </div>
  );
}

function DraftActionCards({
  messageId,
  actions,
  applyingActionId,
  onApplyDraftAction
}: {
  messageId: string;
  actions: AiDraftAction[];
  applyingActionId: string | null;
  onApplyDraftAction: (action: AiDraftAction, messageId: string) => void;
}) {
  const visibleActions = actions.filter((action) => action.type !== 'OPEN_ROUTE');
  if (!visibleActions.length) return null;

  return (
    <div className="mt-2 rounded-lg border border-emerald-100 bg-emerald-50 p-3">
      <div className="mb-2 flex items-center gap-2 text-xs font-black text-emerald-700">
        <ShoppingCart size={14} />
        견적 장바구니 자동 실행
      </div>
      <div className="grid gap-2">
        {visibleActions.map((action) => {
          const applied = action.status === 'APPLIED';
          const failed = action.status === 'FAILED';
          const applying = action.status === 'APPLYING' || applyingActionId === action.id;
          const informational = action.type === 'ASK_FOLLOW_UP';
          return (
            <div key={action.id} className="rounded-lg border border-commerce-line bg-white p-3">
              <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0">
                  <div className="text-sm font-black text-commerce-ink">{action.label}</div>
                  {action.description ? (
                    <p className="mt-1 break-keep text-xs leading-5 text-slate-500">{action.description}</p>
                  ) : null}
                  {failed ? (
                    <p className="mt-1 text-xs font-black text-red-600">자동 실행 실패. 다시 시도해 주세요.</p>
                  ) : null}
                  {applied ? (
                    <p className="mt-1 text-xs font-black text-emerald-700">견적 장바구니에 적용됨</p>
                  ) : null}
                  {!applied && !failed && !informational ? (
                    <p className="mt-1 text-xs font-black text-slate-500">{applying ? '자동 실행 중' : '자동 실행 대기'}</p>
                  ) : null}
                </div>
                {failed && !informational ? (
                  <button
                    type="button"
                    disabled={applying || Boolean(applyingActionId)}
                    onClick={() => onApplyDraftAction(action, messageId)}
                    className="min-h-9 shrink-0 rounded-md bg-commerce-ink px-3 text-xs font-black text-white transition hover:bg-slate-700 disabled:bg-slate-300"
                  >
                    재시도
                  </button>
                ) : null}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
