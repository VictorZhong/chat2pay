import { BRAND_ICON_SRC } from '@/shared/config/brand';
import { cn } from '@/shared/lib/cn';

export function BrandMark({
  size = 'md',
  className,
}: {
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}) {
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center justify-center border border-brand-black bg-white',
        size === 'sm' && 'h-9 w-9 p-1.5',
        size === 'md' && 'h-11 w-11 p-2',
        size === 'lg' && 'h-16 w-16 p-2.5',
        className,
      )}
    >
      <img src={BRAND_ICON_SRC} alt="" className="h-full w-full object-contain" aria-hidden="true" />
    </span>
  );
}
