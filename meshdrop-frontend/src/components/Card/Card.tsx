import React from 'react';
import './Card.css';

export interface CardProps extends Omit<React.HTMLAttributes<HTMLDivElement>, 'title'> {
  title?: React.ReactNode;
  subtitle?: React.ReactNode;
  headerAction?: React.ReactNode;
  footer?: React.ReactNode;
  noPadding?: boolean;
}

export const Card: React.FC<CardProps> = ({
  title,
  subtitle,
  headerAction,
  footer,
  noPadding = false,
  children,
  className = '',
  ...rest
}) => {
  const hasHeader = title || subtitle || headerAction;

  return (
    <div className={`card ${className}`.trim()} {...rest}>
      {hasHeader && (
        <header className="card-header">
          <div>
            {title && <h2 className="card-title">{title}</h2>}
            {subtitle && <p className="card-subtitle">{subtitle}</p>}
          </div>
          {headerAction && <div className="card-header-action">{headerAction}</div>}
        </header>
      )}
      <div className={noPadding ? '' : 'card-body'}>{children}</div>
      {footer && <footer className="card-footer">{footer}</footer>}
    </div>
  );
};
