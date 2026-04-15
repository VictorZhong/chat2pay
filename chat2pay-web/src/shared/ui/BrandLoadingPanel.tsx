import { BrandAvatar } from '@/shared/ui/BrandAvatar';
import { cn } from '@/shared/lib/cn';

export function BrandLoadingPanel({
  title = 'Processing request',
  description = 'The workspace is validating the latest instruction and preparing the next structured response.',
  compact = false,
}: {
  title?: string;
  description?: string;
  compact?: boolean;
}) {
  return (
    <div className={cn('flex gap-4', compact && 'items-center')}>
      <BrandAvatar name="Assistant" size="sm" />
      <div className={cn('brand-panel w-full', compact ? 'px-5 py-4' : 'p-5')}>
        <div className="mb-4 flex items-center justify-between gap-4">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-brand-red">Assistant</p>
            <h4 className="mt-2 text-sm font-semibold text-brand-black">{title}</h4>
          </div>
          <div className="brand-loading-track">
            <span className="brand-loading-bar" />
            <span className="brand-loading-bar" />
            <span className="brand-loading-bar" />
          </div>
        </div>
        <p className="max-w-2xl text-sm leading-7 text-brand-gray">{description}</p>
      </div>
    </div>
  );
}
