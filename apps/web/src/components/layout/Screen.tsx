import { ReactNode } from 'react';
import { AppHeader } from './AppHeader';

export function Screen({ children }: { children: ReactNode }) {
  return (
    <div className="screen-shell">
      <AppHeader />
      <main className="mx-auto w-full max-w-[1550px] px-4 py-6 sm:px-6 lg:px-8 xl:px-0">{children}</main>
    </div>
  );
}
