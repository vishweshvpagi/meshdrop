import { useCallback, useEffect, useRef, useState } from 'react';
import { meshDropApi } from '../services/meshdropApi';
import { Connection } from '../types/Connection';

export interface UseConnectionsResult {
  connections: Connection[];
  isLoading: boolean;
  error: Error | null;
  lastUpdated: Date | null;
  isStale: boolean;
  refresh: () => Promise<void>;
}

export function useConnections(pollIntervalMs: number = 3000): UseConnectionsResult {
  const [connections, setConnections] = useState<Connection[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const isMountedRef = useRef(true);

  const fetchConnections = useCallback(async () => {
    try {
      const data = await meshDropApi.getConnections();
      if (!isMountedRef.current) return;
      setConnections(data);
      setError(null);
      setLastUpdated(new Date());
    } catch (err: unknown) {
      if (!isMountedRef.current) return;
      setError(err instanceof Error ? err : new Error(String(err)));
    } finally {
      if (isMountedRef.current) {
        setIsLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    isMountedRef.current = true;
    fetchConnections();

    const intervalId = setInterval(() => {
      if (typeof document !== 'undefined' && document.hidden) {
        return;
      }
      fetchConnections();
    }, pollIntervalMs);

    const handleVisibilityChange = () => {
      if (typeof document !== 'undefined' && !document.hidden && isMountedRef.current) {
        fetchConnections();
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
  }, [fetchConnections, pollIntervalMs]);

  const isStale = error !== null;

  return {
    connections,
    isLoading,
    error,
    lastUpdated,
    isStale,
    refresh: fetchConnections,
  };
}
