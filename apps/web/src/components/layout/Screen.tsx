import { ReactNode } from 'react';
import { AppHeader } from './AppHeader';

export function Screen({
  children,
  mainClassName = 'mx-auto w-full max-w-[1550px] px-4 py-6 sm:px-6 lg:px-8 xl:px-0'
}: {
  children: ReactNode;
  mainClassName?: string;
}) {
  return (
    <div className="screen-shell">
      <AppHeader />
      <main className={mainClassName}>{children}</main>
    </div>
  );
}
