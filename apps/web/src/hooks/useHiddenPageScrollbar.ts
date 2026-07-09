import { useEffect } from 'react';

const HIDDEN_PAGE_SCROLLBAR_CLASS = 'page-scrollbar-hidden';

let hiddenScrollbarUsers = 0;

export function useHiddenPageScrollbar(active = true) {
  useEffect(() => {
    if (!active) {
      return;
    }

    hiddenScrollbarUsers += 1;
    document.documentElement.classList.add(HIDDEN_PAGE_SCROLLBAR_CLASS);

    return () => {
      hiddenScrollbarUsers = Math.max(0, hiddenScrollbarUsers - 1);
      if (hiddenScrollbarUsers === 0) {
        document.documentElement.classList.remove(HIDDEN_PAGE_SCROLLBAR_CLASS);
      }
    };
  }, [active]);
}
