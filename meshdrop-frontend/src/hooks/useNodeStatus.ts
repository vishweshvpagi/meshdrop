import { useCallback, useEffect, useRef, useState } from 'react';
import { meshDropApi } from '../services/meshdropApi';
import { NodeStatus } from '../types/Node';

export type BackendConnectionStatus =
  | 'UNKNOWN'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'DISCONNECTED'
  | 'ERROR';

export interface UseNodeStatusResult {
  node: NodeStatus | null;
  connectionStatus: BackendConnectionStatus;
  error: Error | null;
  lastUpdated: Date | null;
  isStale: boolean;
  refresh: () => Promise<void>;
}

export function useNodeStatus(pollIntervalMs: number = 3000): UseNodeStatusResult {
  const [node, setNode] = useState<NodeStatus | null>(null);
  const [connectionStatus, setConnectionStatus] = useState<BackendConnectionStatus>('CONNECTING');
  const [error, setError] = useState<Error | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const isMountedRef = useRef(true);

  const fetchStatus = useCallback(async () => {
    try {
      const data = await meshDropApi.getStatus();
      if (!isMountedRef.current) return;
      setNode(data);
      setConnectionStatus('CONNECTED');
      setError(null);
      setLastUpdated(new Date());
    } catch (err: unknown) {
      if (!isMountedRef.current) return;
      const errorObj = err instanceof Error ? err : new Error(String(err));
      setError(errorObj);
      setConnectionStatus('DISCONNECTED');
    }
  }, []);

  useEffect(() => {
    isMountedRef.current = true;
    fetchStatus();

    // Polling with Page Visibility optimization
    const intervalId = setInterval(() => {
      if (typeof document !== 'undefined' && document.hidden) {
        return; // Throttle polling when browser tab is inactive
      }
      fetchStatus();
    }, pollIntervalMs);

    const handleVisibilityChange = () => {
      if (typeof document !== 'undefined' && !document.hidden && isMountedRef.current) {
        fetchStatus();
      }
    };

    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', handleVisibilityChange);
    }

    return () => {
      isMountedRef.current = false;
      clearInterval(intervalId);
      if (typeof document !== 'undefined') {
        document.removeEventListener('visibilitychange', handleVisibilityChange);
      }
    };
  }, [fetchStatus, pollIntervalMs]);

  const isStale = connectionStatus === 'DISCONNECTED' || connectionStatus === 'ERROR';

  return {
    node,
    connectionStatus,
    error,
    lastUpdated,
    isStale,
    refresh: fetchStatus,
  };
}
