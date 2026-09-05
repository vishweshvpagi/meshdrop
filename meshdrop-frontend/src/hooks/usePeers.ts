import { useCallback, useEffect, useRef, useState } from 'react';
import { meshDropApi } from '../services/meshdropApi';
import { Peer } from '../types/Peer';

export interface UsePeersResult {
  peers: Peer[];
  isLoading: boolean;
  error: Error | null;
  lastUpdated: Date | null;
  isStale: boolean;
  refresh: () => Promise<void>;
  connectPeer: (peerId: string) => Promise<{ success: boolean; connectionId?: number; error?: string }>;
  disconnectPeer: (peerId: string) => Promise<{ success: boolean; disconnected?: boolean; error?: string }>;
}

export function usePeers(pollIntervalMs: number = 3000): UsePeersResult {
  const [peers, setPeers] = useState<Peer[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const isMountedRef = useRef(true);

  const fetchPeers = useCallback(async () => {
    try {
      const data = await meshDropApi.getPeers();
      if (!isMountedRef.current) return;
      setPeers(data);
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

  const connectPeer = useCallback(
    async (peerId: string) => {
      const result = await meshDropApi.connectPeerById(peerId);
      await fetchPeers();
      return result;
    },
    [fetchPeers]
  );

  const disconnectPeer = useCallback(
    async (peerId: string) => {
      const result = await meshDropApi.disconnectPeer(peerId);
      await fetchPeers();
      return result;
    },
    [fetchPeers]
  );

  useEffect(() => {
    isMountedRef.current = true;
    fetchPeers();

    const intervalId = setInterval(() => {
      if (typeof document !== 'undefined' && document.hidden) {
        return;
      }
      fetchPeers();
    }, pollIntervalMs);

    const handleVisibilityChange = () => {
      if (typeof document !== 'undefined' && !document.hidden && isMountedRef.current) {
        fetchPeers();
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
  }, [fetchPeers, pollIntervalMs]);

  const isStale = error !== null;

  return {
    peers,
    isLoading,
    error,
    lastUpdated,
    isStale,
    refresh: fetchPeers,
    connectPeer,
    disconnectPeer,
  };
}
