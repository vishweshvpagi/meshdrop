/**
 * Formats a byte count into a human-readable string (B, KB, MB, GB, TB, PB).
 * Supports number, bigint, and string representations safely without 32-bit truncation.
 */
export function formatBytes(bytes: number | bigint | string | undefined | null, decimals: number = 1): string {
  if (bytes === undefined || bytes === null) return '0 B';
  const num = typeof bytes === 'bigint' ? Number(bytes) : typeof bytes === 'string' ? Number(bytes) : bytes;
  if (!isFinite(num) || num <= 0) return '0 B';

  const k = 1024;
  const dm = Math.max(0, decimals);
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];

  const i = Math.floor(Math.log(num) / Math.log(k));
  const safeIndex = Math.min(Math.max(i, 0), sizes.length - 1);
  const value = num / Math.pow(k, safeIndex);
  return `${parseFloat(value.toFixed(dm))} ${sizes[safeIndex]}`;
}

/**
 * Formats transfer speed into human-readable string (e.g. 12.4 MB/s).
 * If speed is undefined or <= 0, returns '--' or '0 B/s'.
 */
export function formatSpeed(bytesPerSecond?: number | bigint | string | null): string {
  if (bytesPerSecond === undefined || bytesPerSecond === null) return '--';
  const num = typeof bytesPerSecond === 'bigint' ? Number(bytesPerSecond) : typeof bytesPerSecond === 'string' ? Number(bytesPerSecond) : bytesPerSecond;
  if (!isFinite(num) || num < 0) return '--';
  if (num === 0) return '0 B/s';
  return `${formatBytes(num, 1)}/s`;
}

/**
 * Formats ETA seconds into a concise representation (e.g. 1m 24s, 45s).
 */
export function formatEta(seconds?: number | string | null): string {
  if (seconds === undefined || seconds === null) return '--';
  const num = typeof seconds === 'string' ? Number(seconds) : seconds;
  if (!isFinite(num) || num < 0) {
    return '--';
  }
  if (num === 0) return '0s';

  const hrs = Math.floor(num / 3600);
  const mins = Math.floor((num % 3600) / 60);
  const secs = Math.floor(num % 60);

  if (hrs > 0) {
    return `${hrs}h ${mins}m`;
  }
  if (mins > 0) {
    return `${mins}m ${secs}s`;
  }
  return `${secs}s`;
}

/**
 * Formats uptime or duration milliseconds into human-readable format (e.g. 2h 15m, 45s).
 */
export function formatUptime(millis?: number): string {
  if (millis === undefined || millis === null || millis < 0 || !isFinite(millis)) {
    return '--';
  }
  const totalSeconds = Math.floor(millis / 1000);
  if (totalSeconds < 1) return '< 1s';

  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (days > 0) {
    return `${days}d ${hours}h ${minutes}m`;
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  if (minutes > 0) {
    return `${minutes}m ${seconds}s`;
  }
  return `${seconds}s`;
}

/**
 * Calculates transfer completion percentage clamped between 0 and 100.
 * Supports number, bigint, and string safely without 32-bit truncation.
 */
export function calculatePercentage(
  transferred: number | bigint | string | undefined | null,
  total: number | bigint | string | undefined | null
): number {
  const trans = Number(transferred);
  const tot = Number(total);
  if (!tot || tot <= 0 || !isFinite(tot) || !isFinite(trans) || trans <= 0) return 0;
  const pct = Math.round((trans / tot) * 100);
  return Math.min(Math.max(pct, 0), 100);
}

/**
 * Maps low-level backend state enums to friendly display labels.
 */
export function formatTransferState(state: string | undefined | null): string {
  switch (state) {
    case 'OFFERING':
      return 'Offering';
    case 'WAITING_FOR_ACCEPT':
      return 'Waiting for Accept';
    case 'ACCEPTED':
      return 'Accepted';
    case 'TRANSFERRING':
      return 'Transferring';
    case 'VERIFYING':
      return 'Verifying';
    case 'INTERRUPTED':
      return 'Interrupted';
    case 'RESUMABLE':
      return 'Resumable';
    case 'RESUMING':
      return 'Resuming';
    case 'COMPLETED':
      return 'Completed';
    case 'FAILED':
      return 'Failed';
    case 'CANCELLED':
      return 'Cancelled';
    case 'REJECTED':
      return 'Rejected';
    case 'TIMED_OUT':
      return 'Timed Out';
    default:
      return state || 'Unknown';
  }
}

/**
 * Formats epoch millisecond timestamp to local date/time string.
 */
export function formatTimestamp(timestampMs?: number | string | null): string {
  if (!timestampMs) return '--';
  const num = typeof timestampMs === 'string' ? Number(timestampMs) : timestampMs;
  if (!num || !isFinite(num) || num <= 0) return '--';
  try {
    return new Date(num).toLocaleString();
  } catch {
    return '--';
  }
}

/**
 * Formats epoch millisecond or Date into concise relative time (e.g. 5s ago, 2m ago).
 */
export function formatRelativeTime(date?: Date | number | string | null): string {
  if (!date) return '';
  const d = typeof date === 'number' || typeof date === 'string' ? new Date(date) : date;
  const now = Date.now();
  const diffSec = Math.floor((now - d.getTime()) / 1000);
  if (diffSec < 5) return 'just now';
  if (diffSec < 60) return `${diffSec}s ago`;
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHours = Math.floor(diffMin / 60);
  if (diffHours < 24) return `${diffHours}h ago`;
  return formatTimestamp(d.getTime());
}
