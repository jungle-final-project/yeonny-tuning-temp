import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * 스크롤 컨테이너를 "바닥에 붙여" 따라가게 한다 — AI 답변이 단계별로 채워지며 높이가 자라는 동안에도
 * 최신 글자까지 추적한다. 사용자가 위로 올려 읽는 중에는 추적을 멈추고(강제 스크롤 금지),
 * 그 사이 새 내용이 오면 hasNewBelow로 알려 "새 메시지" 버튼을 띄울 수 있게 한다.
 *
 * 메시지 개수 변화만 보는 방식(scrollIntoView on messages.length)은 한 답변이 자라는 동안
 * 아무 것도 하지 않아 긴 답변의 뒷부분을 놓친다 — 그래서 내용 변화(Mutation)와 크기 변화(Resize)를 본다.
 */
export function useStickToBottom(options?: { thresholdPx?: number }) {
  const thresholdPx = options?.thresholdPx ?? 48;
  // 컨테이너를 state로 들고 있어야 표면이 바뀔 때(중앙 모달 ↔ 도킹 패널) 관찰자가 새 노드에 다시 붙는다.
  const [container, setContainer] = useState<HTMLDivElement | null>(null);
  const pinnedRef = useRef(true);
  const [isPinned, setIsPinned] = useState(true);
  const [hasNewBelow, setHasNewBelow] = useState(false);

  const setPinned = useCallback((pinned: boolean) => {
    pinnedRef.current = pinned;
    setIsPinned(pinned);
    if (pinned) {
      setHasNewBelow(false);
    }
  }, []);

  // 이미 붙어 있는 노드가 다시 들어오면 아무 것도 하지 않는다 — 재부착마다 바닥으로 끌어내리면
  // 사용자가 위로 올려 읽던 위치가 리렌더 때마다 초기화된다(#255 회귀의 방어선).
  const attachedRef = useRef<HTMLDivElement | null>(null);
  const containerRef = useCallback((node: HTMLDivElement | null) => {
    if (attachedRef.current === node) return;
    attachedRef.current = node;
    setContainer(node);
    if (!node) return;
    // 새 표면이 열리면 최신 내용부터 보여준다(기존 동작 유지).
    node.scrollTop = node.scrollHeight;
    pinnedRef.current = true;
    setIsPinned(true);
    setHasNewBelow(false);
  }, []);

  /** 바닥으로 이동. 자동 추적은 instant(자라는 속도와 애니메이션이 싸우지 않게), 버튼 클릭만 smooth. */
  const scrollToBottom = useCallback((smooth = false) => {
    if (!container) return;
    const reduceMotion = typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (smooth && !reduceMotion) {
      container.scrollTo({ top: container.scrollHeight, behavior: 'smooth' });
    } else {
      container.scrollTop = container.scrollHeight;
    }
    setPinned(true);
  }, [container, setPinned]);

  useEffect(() => {
    if (!container) return;

    const isAtBottom = () => (
      container.scrollHeight - container.scrollTop - container.clientHeight <= thresholdPx
    );

    const follow = () => {
      if (pinnedRef.current) {
        container.scrollTop = container.scrollHeight;
      } else {
        // 사용자가 위에서 읽는 중 — 끌어내리지 않고 새 내용이 생겼다는 사실만 알린다.
        setHasNewBelow(true);
      }
    };

    // 프로그래매틱 스크롤도 같은 규칙으로 판정되므로 별도 억제 플래그가 필요 없다.
    const onScroll = () => setPinned(isAtBottom());
    container.addEventListener('scroll', onScroll, { passive: true });

    // 이미지 로드·패널 리사이즈처럼 DOM 변경 없이 높이만 바뀌는 경우까지 잡으려면
    // 컨테이너뿐 아니라 각 메시지(직계 자식)도 관찰해야 한다 — 새 메시지는 아래 mutation에서 추가 관찰한다.
    const resizeObserver = new ResizeObserver(follow);
    const observed = new WeakSet<Element>();
    const observeChildren = () => {
      Array.from(container.children).forEach((child) => {
        if (observed.has(child)) return;
        observed.add(child);
        resizeObserver.observe(child);
      });
    };
    resizeObserver.observe(container);
    observeChildren();

    // 답변 텍스트가 붙거나(characterData) 카드 섹션이 하나씩 나타날 때(childList) 높이가 자란다.
    const mutationObserver = new MutationObserver(() => {
      observeChildren();
      follow();
    });
    mutationObserver.observe(container, { childList: true, subtree: true, characterData: true });

    return () => {
      container.removeEventListener('scroll', onScroll);
      mutationObserver.disconnect();
      resizeObserver.disconnect();
    };
  }, [container, setPinned, thresholdPx]);

  return { containerRef, isPinned, hasNewBelow, scrollToBottom };
}
