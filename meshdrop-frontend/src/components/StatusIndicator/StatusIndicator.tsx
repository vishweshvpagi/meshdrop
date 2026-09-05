import React from 'react';
import './StatusIndicator.css';

export type StatusType =
  | 'online'
  | 'connected'
  | 'offline'
  | 'disconnected'
  | 'connecting'
  | 'transferring'
  | 'resumable'
  | 'failed';

export interface StatusIndicatorProps {
  status: StatusType;
  label?: string;
  className?: string;
}

const defaultLabels: Record<StatusType, string> = {
  online: 'Online',
  connected: 'Connected',
  offline: 'Offline',
  disconnected: 'Disconnected',
  connecting: 'Connecting...',
  transferring: 'Transferring',
  resumable: 'Resumable',
  failed: 'Failed',
};

export const StatusIndicator: React.FC<StatusIndicatorProps> = ({
  status,
  label,
  className = '',
}) => {
  const displayLabel = label || defaultLabels[status];

  return (
    <span className={`status-indicator status-${status} ${className}`.trim()}>
      <span className="status-dot" aria-hidden="true" />
      <span className="status-text">{displayLabel}</span>
    </span>
  );
};
