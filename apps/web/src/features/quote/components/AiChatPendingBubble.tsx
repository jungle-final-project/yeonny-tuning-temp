type AiChatPendingBubbleSize = 'default' | 'large';

type AiChatPendingBubbleProps = {
  // 진행 중인 사용자 요청의 앞부분(최대 30자, 호출부에서 이미 잘림). JSX 텍스트 노드로 렌더돼 자동 escape된다.
  excerpt: string;
  size?: AiChatPendingBubbleSize;
};

// AI 챗봇이 응답을 준비하는 동안 대화 안에 표시하는 임시 assistant 상태 행.
// 실제 진행률을 알 수 없으므로 가짜 %나 내부 단계(RAG/DB/LLM) 문구는 노출하지 않는다.
// 애니메이션은 Tailwind 내장(motion-safe:animate-bounce)만 사용 — reduced-motion에서는 자동으로 정지한다.
export function AiChatPendingBubble({ excerpt, size = 'default' }: AiChatPendingBubbleProps) {
  const isLarge = size === 'large';
  return (
    <div
      data-testid="ai-chat-pending"
      data-response-surface="plain"
      role="status"
      aria-live="polite"
      className={
        isLarge
          ? 'px-1 py-2 text-white/70'
          : 'px-1 py-1 text-slate-500'
      }
    >
      {excerpt ? (
        <p
          data-testid="ai-chat-pending-excerpt"
          className={isLarge ? 'text-[18px] font-bold leading-7 text-white/85' : 'text-[13px] font-bold text-slate-600'}
        >
          “{excerpt}”
        </p>
      ) : null}
      <div className={`flex items-center gap-2 ${excerpt ? 'mt-1' : ''}`}>
        <span className={isLarge ? 'text-[18px] font-bold leading-7' : 'text-sm font-bold'}>
          답변을 준비하고 있어요
        </span>
        <span className="flex items-center gap-1" aria-hidden="true">
          {[0, 1, 2].map((index) => (
            <span
              key={index}
              data-testid="ai-chat-pending-dot"
              className={`inline-block rounded-full motion-safe:animate-bounce ${isLarge ? 'h-2 w-2 bg-white/60' : 'h-1.5 w-1.5 bg-slate-400'}`}
              style={{ animationDelay: `${index * 150}ms` }}
            />
          ))}
        </span>
      </div>
    </div>
  );
}
