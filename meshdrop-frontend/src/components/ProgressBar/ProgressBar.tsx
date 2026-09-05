import React from 'react';
import './ProgressBar.css';

export interface ProgressBarProps {
  progress: number; // 0 to 100
  variant?: 'primary' | 'success' | 'warning' | 'error';
  showLabel?: boolean;
  label?: React.ReactNode;
  height?: number;
  className?: string;
}

export const ProgressBar: React.FC<ProgressBarProps> = ({
  progress,
  variant = 'primary',
  showLabel = false,
  label,
  height,
  className = '',
}) => {
  // Guarantee progress is clamped strictly between 0% and 100%
  const clampedProgress = Math.min(Math.max(Math.round(progress), 0), 100);

  const fillClass = [
    'progress-bar-fill',
    variant !== 'primary' ? `is-${variant}` : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={`progress-bar-container ${className}`.trim()}>
      {(showLabel || label) && (
        <div className="progress-bar-info">
          <span>{label || 'Progress'}</span>
          <span>{clampedProgress}%</span>
        </div>
      )}
      <div
        className="progress-bar-track"
        style={height ? { height: `${height}px` } : undefined}
        role="progressbar"
        aria-valuenow={clampedProgress}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <div
          className={fillClass}
          style={{ width: `${clampedProgress}%` }}
        />
      </div>
    </div>
  );
};
