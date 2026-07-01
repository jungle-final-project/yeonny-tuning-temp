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
  type AiRecommendedBuild
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
      await applyAiBuildToQuoteDraft({
        buildId: normalizedBuild.id,
        conflictPolicy: 'REPLACE',
        items: normalizedBuild.items.map((item) => ({
          partId: item.partId,
          category: item.category,
          quantity: item.quantity
        }))
      });
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
    setApplyError(null);
    setApplyingActionId(action.id);
    markDraftActionStatus(messageId, action.id, 'APPLYING');
    try {
      const partId = typeof action.payload.partId === 'string' ? action.payload.partId : null;
      const quantity = typeof action.payload.quantity === 'number' ? action.payload.quantity : 1;
      if (action.type === 'ASK_FOLLOW_UP') {
        markDraftActionStatus(messageId, action.id, 'APPLIED');
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
  return (
    <div className="mt-2 rounded-lg border border-emerald-100 bg-emerald-50 p-3">
      <div className="mb-2 flex items-center gap-2 text-xs font-black text-emerald-700">
        <ShoppingCart size={14} />
        견적 장바구니 변경안
      </div>
      <div className="grid gap-2">
        {actions.map((action) => {
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
                    <p className="mt-1 text-xs font-black text-red-600">적용 실패. 다시 시도해 주세요.</p>
                  ) : null}
                  {applied ? (
                    <p className="mt-1 text-xs font-black text-emerald-700">견적 장바구니에 적용됨</p>
                  ) : null}
                </div>
                {!informational ? (
                  <button
                    type="button"
                    disabled={applying || applied || Boolean(applyingActionId)}
                    onClick={() => onApplyDraftAction(action, messageId)}
                    className="min-h-9 shrink-0 rounded-md bg-commerce-ink px-3 text-xs font-black text-white transition hover:bg-slate-700 disabled:bg-slate-300"
                  >
                    {applied ? '완료' : applying ? '적용 중' : '적용'}
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
