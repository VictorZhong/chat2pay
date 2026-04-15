import type { PropsWithChildren } from 'react';

export function EmptyStatePanel({
  eyebrow,
  title,
  description,
  children,
}: PropsWithChildren<{
  eyebrow: string;
  title: string;
  description: string;
}>) {
  return (
    <div className="brand-panel relative mx-auto max-w-3xl p-10">
      <div className="absolute left-0 top-0 h-1 w-20 bg-brand-red" />
      <p className="mb-4 text-xs font-semibold uppercase tracking-[0.2em] text-brand-red">{eyebrow}</p>
      <h2 className="mb-4 text-3xl font-semibold text-brand-black">{title}</h2>
      <p className="max-w-2xl text-sm leading-7 text-brand-gray">{description}</p>
      {children ? <div className="mt-8">{children}</div> : null}
    </div>
  );
}
