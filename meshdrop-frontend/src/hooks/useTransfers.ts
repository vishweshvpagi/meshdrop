import { useCallback, useEffect, useRef, useState } from 'react';
import { meshDropApi } from '../services/meshdropApi';
import { Transfer, TransferState } from '../types/Transfer';

export interface TransferCounters {
  active: number;
  resumable: number;
  completed: number;
  failed: number;
  total: number;
}

export interface UseTransfersResult {
  transfers: Transfer[];
  activeTransfers: Transfer[];
  resumableTransfers: Transfer[];
  completedTransfers: Transfer[];
  failedTransfers: Transfer[];
  recentTransfers: Transfer[];
  counters: TransferCounters;
  isLoading: boolean;
  error: Error | null;
  lastUpdated: Date | null;
  isStale: boolean;
  refresh: () => Promise<void>;
  startTransfer: (peerId: string, filePath: string) => Promise<{ success: boolean; transferId?: string; error?: string }>;
  resumeTransfer: (transferId: string) => Promise<{ success: boolean; transferId?: string; state?: string; error?: string }>;
  retryTransfer: (transferId: string) => Promise<{ success: boolean; transferId?: string; state?: string; error?: string }>;
  cancelTransfer: (transferId: string) => Promise<{ success: boolean; transferId?: string; error?: string }>;
  removeTransfer: (transferId: string) => Promise<{ success: boolean; transferId?: string; error?: string }>;
}

const TERMINAL_STATES: Set<TransferState> = new Set([
  'COMPLETED',
  'REJECTED',
  'FAILED',
  'CANCELLED',
  'TIMED_OUT',
]);

export function isTransferActive(state: TransferState): boolean {
  return !TERMINAL_STATES.has(state) && state !== 'INTERRUPTED' && state !== 'RESUMABLE';
}

export function isTransferResumable(t: Transfer): boolean {
  if (t.canResume !== undefined) return t.canResume;
  const s = t.state || t.status;
  return s === 'RESUMABLE' || s === 'INTERRUPTED';
}

export function useTransfers(basePollIntervalMs: number = 1500): UseTransfersResult {
  const [transfers, setTransfers] = useState<Transfer[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const isMountedRef = useRef(true);

  const fetchTransfers = useCallback(async () => {
    try {
      const data = await meshDropApi.getTransfers();
      if (!isMountedRef.current) return;
      // Normalize transfer properties to guarantee consistency
      const normalized = (data || []).map((t) => ({
        ...t,
        id: t.transferId || t.id,
        transferId: t.transferId || t.id,
        state: t.state || t.status,
        status: t.state || t.status,
        speed: t.speedBytesPerSecond ?? t.speed,
        speedBytesPerSecond: t.speedBytesPerSecond ?? t.speed,
        eta: t.etaSeconds ?? t.eta,
        etaSeconds: t.etaSeconds ?? t.eta,
        remainingBytes: t.remainingBytes ?? Math.max(0, t.fileSize - t.transferredBytes),
      }));
      setTransfers(normalized);
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

  const startTransfer = useCallback(
    async (peerId: string, filePath: string) => {
      const result = await meshDropApi.startTransfer(peerId, filePath);
      await fetchTransfers();
      return result;
    },
    [fetchTransfers]
  );

  const resumeTransfer = useCallback(
    async (transferId: string) => {
      const result = await meshDropApi.resumeTransfer(transferId);
      await fetchTransfers();
      return result;
    },
    [fetchTransfers]
  );

  const retryTransfer = useCallback(
    async (transferId: string) => {
      const result = await meshDropApi.retryTransfer(transferId);
      await fetchTransfers();
      return result;
    },
    [fetchTransfers]
  );

  const cancelTransfer = useCallback(
    async (transferId: string) => {
      const result = await meshDropApi.cancelTransfer(transferId);
      await fetchTransfers();
      return result;
    },
    [fetchTransfers]
  );

  const removeTransfer = useCallback(
    async (transferId: string) => {
      const result = await meshDropApi.removeTransfer(transferId);
      await fetchTransfers();
      return result;
    },
    [fetchTransfers]
  );

  // Computed collections
  const activeTransfers = transfers.filter((t) => isTransferActive(t.state || t.status));
  const resumableTransfers = transfers.filter((t) => isTransferResumable(t));
  const completedTransfers = transfers.filter((t) => (t.state || t.status) === 'COMPLETED');
  const failedTransfers = transfers.filter((t) => {
    const s = t.state || t.status;
    return s === 'FAILED' || s === 'CANCELLED' || s === 'REJECTED' || s === 'TIMED_OUT';
  });
  const recentTransfers = transfers.filter((t) => !isTransferActive(t.state || t.status));

  const counters: TransferCounters = {
    active: activeTransfers.length,
    resumable: resumableTransfers.length,
    completed: completedTransfers.length,
    failed: failedTransfers.length,
    total: transfers.length,
  };

  // Adaptive polling: 1500ms when transfers are active, 4000ms when idle
  const hasActiveTransfers = activeTransfers.length > 0;
  const currentInterval = hasActiveTransfers ? basePollIntervalMs : Math.max(basePollIntervalMs, 4000);

  useEffect(() => {
    isMountedRef.current = true;
    fetchTransfers();

    const intervalId = setInterval(() => {
      if (typeof document !== 'undefined' && document.hidden) {
        return; // Throttle when hidden
      }
      fetchTransfers();
    }, currentInterval);

    const handleVisibilityChange = () => {
      if (typeof document !== 'undefined' && !document.hidden && isMountedRef.current) {
        fetchTransfers();
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
  }, [fetchTransfers, currentInterval]);

  const isStale = error !== null;

  return {
    transfers,
    activeTransfers,
    resumableTransfers,
    completedTransfers,
    failedTransfers,
    recentTransfers,
    counters,
    isLoading,
    error,
    lastUpdated,
    isStale,
    refresh: fetchTransfers,
    startTransfer,
    resumeTransfer,
    retryTransfer,
    cancelTransfer,
    removeTransfer,
  };
}
