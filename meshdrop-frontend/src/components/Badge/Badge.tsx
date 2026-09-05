import React from 'react';
import './Badge.css';

export type BadgeVariant = 'success' | 'warning' | 'error' | 'info' | 'neutral';

export interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?: BadgeVariant;
  withDot?: boolean;
}

export const Badge: React.FC<BadgeProps> = ({
  variant = 'neutral',
  withDot = false,
  children,
  className = '',
  ...rest
}) => {
  return (
    <span className={`badge badge-${variant} ${className}`.trim()} {...rest}>
      {withDot && <span className="badge-dot" aria-hidden="true" />}
      <span>{children}</span>
    </span>
  );
};
