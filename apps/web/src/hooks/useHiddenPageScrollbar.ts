import { useEffect } from 'react';

const HIDDEN_PAGE_SCROLLBAR_CLASS = 'page-scrollbar-hidden';

let hiddenScrollbarUsers = 0;
let lockedPageScrollUsers = 0;
let previousHtmlOverflow = '';
let previousBodyOverflow = '';
let previousHtmlOverscrollBehavior = '';
let previousBodyOverscrollBehavior = '';

function addHiddenPageScrollbarUser() {
  hiddenScrollbarUsers += 1;
  document.documentElement.classList.add(HIDDEN_PAGE_SCROLLBAR_CLASS);
}

function removeHiddenPageScrollbarUser() {
  hiddenScrollbarUsers = Math.max(0, hiddenScrollbarUsers - 1);
  if (hiddenScrollbarUsers === 0) {
    document.documentElement.classList.remove(HIDDEN_PAGE_SCROLLBAR_CLASS);
  }
}

export function useHiddenPageScrollbar(active = true) {
  useEffect(() => {
    if (!active) {
      return;
    }

    addHiddenPageScrollbarUser();

    return () => {
      removeHiddenPageScrollbarUser();
    };
  }, [active]);
}

export function useLockedPageScroll(active = true) {
  useEffect(() => {
    if (!active) {
      return;
    }

    lockedPageScrollUsers += 1;
    addHiddenPageScrollbarUser();

    if (lockedPageScrollUsers === 1) {
      previousHtmlOverflow = document.documentElement.style.overflow;
      previousBodyOverflow = document.body.style.overflow;
      previousHtmlOverscrollBehavior = document.documentElement.style.overscrollBehavior;
      previousBodyOverscrollBehavior = document.body.style.overscrollBehavior;

      document.documentElement.style.overflow = 'hidden';
      document.body.style.overflow = 'hidden';
      document.documentElement.style.overscrollBehavior = 'none';
      document.body.style.overscrollBehavior = 'none';
    }

    return () => {
      lockedPageScrollUsers = Math.max(0, lockedPageScrollUsers - 1);
      removeHiddenPageScrollbarUser();

      if (lockedPageScrollUsers === 0) {
        document.documentElement.style.overflow = previousHtmlOverflow;
        document.body.style.overflow = previousBodyOverflow;
        document.documentElement.style.overscrollBehavior = previousHtmlOverscrollBehavior;
        document.body.style.overscrollBehavior = previousBodyOverscrollBehavior;
      }
    };
  }, [active]);
}
