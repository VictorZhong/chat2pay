import type { ButtonHTMLAttributes, PropsWithChildren } from 'react';
import { cn } from '@/shared/lib/cn';

type BrandButtonVariant = 'primary' | 'secondary';

type BrandButtonProps = PropsWithChildren<
  ButtonHTMLAttributes<HTMLButtonElement> & {
    variant?: BrandButtonVariant;
    fullWidth?: boolean;
    loading?: boolean;
  }
>;

export function BrandButton({
  children,
  className,
  variant = 'primary',
  fullWidth = false,
  loading = false,
  ...props
}: BrandButtonProps) {
  return (
    <button
      className={cn(
        'brand-button',
        variant === 'secondary' && 'brand-button-secondary',
        fullWidth && 'w-full',
        className,
      )}
      aria-busy={loading}
      {...props}
    >
      {loading ? <span className="brand-button-loader" aria-hidden="true" /> : null}
      {children}
    </button>
  );
}
