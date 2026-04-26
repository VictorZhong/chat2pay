import { cn } from '@/shared/lib/cn';
import { initialsOf } from '@/shared/lib/format';
import { BrandMark } from '@/shared/ui/BrandMark';

export function BrandAvatar({
  name,
  size = 'md',
  variant = 'user',
}: {
  name: string;
  size?: 'sm' | 'md' | 'lg';
  variant?: 'user' | 'assistant';
}) {
  if (variant === 'assistant') {
    return <BrandMark size={size} className="bg-white" />;
  }

  return (
    <div
      className={cn(
        'flex items-center justify-center border border-brand-black bg-brand-red font-semibold text-white',
        size === 'sm' && 'h-9 w-9 text-xs',
        size === 'md' && 'h-12 w-12 text-sm',
        size === 'lg' && 'h-16 w-16 text-base',
      )}
    >
      {initialsOf(name)}
    </div>
  );
}
