import { ReactNode } from 'react';

export function Panel({ title, subtitle, children, className = '' }: { title: string; subtitle?: string; children: ReactNode; className?: string }) {
  return (
    <section className={`panel p-4 sm:p-5 ${className}`}>
      <div className="mb-4">
        <h2 className="text-base font-black text-commerce-ink">{title}</h2>
        {subtitle ? <p className="mt-1 text-xs leading-5 text-slate-500">{subtitle}</p> : null}
      </div>
      {children}
    </section>
  );
}
