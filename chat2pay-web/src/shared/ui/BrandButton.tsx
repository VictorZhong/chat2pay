import type { ButtonHTMLAttributes, PropsWithChildren } from 'react';
import { cn } from '@/shared/lib/cn';

type BrandButtonVariant = 'primary' | 'secondary';

type BrandButtonProps = PropsWithChildren<
  ButtonHTMLAttributes<HTMLButtonElement> & {
    variant?: BrandButtonVariant;
    fullWidth?: boolean;
  }
>;

export function BrandButton({
  children,
  className,
  variant = 'primary',
  fullWidth = false,
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
      {...props}
    >
      {children}
    </button>
  );
}
