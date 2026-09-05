export type TransferState =
  | 'OFFERING'
  | 'WAITING_FOR_ACCEPT'
  | 'ACCEPTED'
  | 'TRANSFERRING'
  | 'VERIFYING'
  | 'COMPLETED'
  | 'REJECTED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMED_OUT'
  | 'INTERRUPTED'
  | 'RESUMABLE'
  | 'RESUMING';

export type TransferDirection = 'OUTGOING' | 'INCOMING' | 'UPLOAD' | 'DOWNLOAD';

export interface Transfer {
  /** Canonical backend transfer UUID */
  transferId?: string;
  /** Primary identifier (UUID) */
  id: string;
  fileName: string;
  fileSize: number;
  transferredBytes: number;
  peerId: string;
  peerName: string;
  direction: TransferDirection;
  /** Canonical state from Java backend state machine */
  state?: TransferState;
  /** Primary status representation */
  status: TransferState;
  /** Instantaneous speed in bytes/sec */
  speedBytesPerSecond?: number;
  /** Backwards-compatible alias for speed */
  speed?: number;
  /** Estimated seconds remaining */
  etaSeconds?: number;
  /** Backwards-compatible alias for eta */
  eta?: number;
  /** Calculated completion percentage [0..100] */
  progressPercentage?: number;
  errorMessage?: string | null;
  /** SHA-256 verification hash */
  sha256?: string | null;
  startTime?: number;
  completedTime?: number;
  startedAt?: string;
  completedAt?: string;
  /** Unsent or unreceived bytes remaining */
  remainingBytes?: number;
  /** Authoritative capability flag: can this transfer be resumed? */
  canResume?: boolean;
  /** Authoritative capability flag: can this transfer be cancelled? */
  canCancel?: boolean;
  /** Authoritative capability flag: can this transfer be retried? */
  canRetry?: boolean;
  /** Authoritative capability flag: can this transfer record be removed from history? */
  canRemove?: boolean;
  /** True if an on-disk recovery checkpoint exists for this transfer */
  hasCheckpoint?: boolean;
  lastUpdated?: number;
}
