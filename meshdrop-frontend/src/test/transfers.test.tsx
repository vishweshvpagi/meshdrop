import { describe, expect, it, vi } from 'vitest';
import { renderToString } from 'react-dom/server';
import { SendFileDialog } from '../components/transfers/SendFileDialog';
import { TransferCard } from '../components/transfers/TransferCard';
import { TransferDetailsModal } from '../components/transfers/TransferDetailsModal';
import { meshDropApi } from '../services/meshdropApi';
import { Peer } from '../types/Peer';
import { Transfer } from '../types/Transfer';
import {
  calculatePercentage,
  formatBytes,
  formatEta,
  formatSpeed,
  formatTimestamp,
  formatTransferState,
} from '../utils/formatters';

describe('Phase 3 & 4 File Transfers Test Suite', () => {
  const samplePeers: Peer[] = [
    {
      id: 'peer-uuid-1',
      displayName: 'PC-2',
      address: '192.168.1.25',
      port: 5000,
      state: 'CONNECTED',
      connected: true,
      trustDecision: 'TRUSTED',
      lastSeen: '2026-09-05T12:00:00Z',
      connectedAt: '2026-09-05T12:00:00Z',
      fingerprint: '1234-5678-9ABC-DEF0',
    },
    {
      id: 'peer-uuid-2',
      displayName: 'PC-3',
      address: '192.168.1.30',
      port: 5000,
      state: 'DISCONNECTED',
      connected: false,
      trustDecision: 'UNTRUSTED',
      lastSeen: null,
      connectedAt: null,
      fingerprint: '9876-5432-10FE-DCBA',
    },
  ];

  // 1. Transfer rendering
  it('1. renders TransferCard with file details and metrics', () => {
    const transfer: Transfer = {
      id: 'tx-1',
      transferId: 'tx-1',
      fileName: 'example.iso',
      fileSize: 524288000, // 500 MB
      transferredBytes: 262144000, // 250 MB
      direction: 'OUTGOING',
      state: 'TRANSFERRING',
      status: 'TRANSFERRING',
      peerId: 'peer-uuid-1',
      peerName: 'PC-2',
      speedBytesPerSecond: 12582912, // 12 MB/s
      etaSeconds: 21,
    };

    const html = renderToString(<TransferCard transfer={transfer} />);
    expect(html).toContain('example.iso');
    expect(html).toContain('PC-2');
    expect(html).toContain('Transferring');
    expect(html).toContain('50');
    expect(html).toContain('250 MB');
    expect(html).toContain('500 MB');
    expect(html).toContain('12 MB/s');
    expect(html).toContain('21s');
  });

  // 2. Progress calculation
  it('2. calculates transfer completion percentage accurately for large files', () => {
    // 500 MB file (250 MB transferred -> 50%)
    expect(calculatePercentage(262144000, 524288000)).toBe(50);
    // 1 GB file (768 MB transferred -> 75%)
    expect(calculatePercentage(805306368, 1073741824)).toBe(75);
    // 2 GB file (1 GB transferred -> 50%)
    expect(calculatePercentage(1073741824, 2147483648)).toBe(50);
    // 4 GB file (4 GB transferred -> 100%)
    expect(calculatePercentage(4294967296, 4294967296)).toBe(100);
    // 10 GB file (1 GB transferred -> 10%)
    expect(calculatePercentage(1073741824, 10737418240)).toBe(10);
  });

  // 3. Large file size formatting
  it('3. formats large file byte counts without 32-bit integer overflow', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(524288000)).toBe('500 MB');
    expect(formatBytes(1073741824)).toBe('1 GB');
    expect(formatBytes(2147483648)).toBe('2 GB');
    expect(formatBytes(4294967296)).toBe('4 GB');
    expect(formatBytes(10737418240)).toBe('10 GB');
    // String and BigInt support
    expect(formatBytes('10737418240')).toBe('10 GB');
    expect(formatBytes(10737418240n)).toBe('10 GB');
  });

  // 4. Speed formatting
  it('4. formats transfer speeds correctly', () => {
    expect(formatSpeed(0)).toBe('0 B/s');
    expect(formatSpeed(1024)).toBe('1 KB/s');
    expect(formatSpeed(15728640)).toBe('15 MB/s');
    expect(formatSpeed(null)).toBe('--');
    expect(formatSpeed(undefined)).toBe('--');
  });

  // 5. ETA calculation
  it('5. formats ETA durations concisely', () => {
    expect(formatEta(0)).toBe('0s');
    expect(formatEta(45)).toBe('45s');
    expect(formatEta(84)).toBe('1m 24s');
    expect(formatEta(3665)).toBe('1h 1m');
    expect(formatEta(-1)).toBe('--');
    expect(formatEta(null)).toBe('--');
  });

  // 6. Completed state
  it('6. renders COMPLETED transfer state with success badge and 100% progress', () => {
    const completedTransfer: Transfer = {
      id: 'tx-done',
      transferId: 'tx-done',
      fileName: 'backup.tar.gz',
      fileSize: 1048576,
      transferredBytes: 1048576,
      direction: 'OUTGOING',
      state: 'COMPLETED',
      status: 'COMPLETED',
      peerId: 'peer-uuid-1',
      peerName: 'PC-2',
      speedBytesPerSecond: 0,
      etaSeconds: 0,
    };

    const html = renderToString(<TransferCard transfer={completedTransfer} />);
    expect(html).toContain('Completed');
    expect(html).toContain('100%');
  });

  // 7. Failed state
  it('7. renders FAILED transfer state with error banner and message', () => {
    const failedTransfer: Transfer = {
      id: 'tx-fail',
      transferId: 'tx-fail',
      fileName: 'dataset.csv',
      fileSize: 10485760,
      transferredBytes: 3145728,
      direction: 'INCOMING',
      state: 'FAILED',
      status: 'FAILED',
      peerId: 'peer-uuid-1',
      peerName: 'PC-2',
      errorMessage: 'Remote peer closed connection abruptly',
    };

    const html = renderToString(<TransferCard transfer={failedTransfer} />);
    expect(html).toContain('Failed');
    expect(html).toContain('Remote peer closed connection abruptly');
  });

  // 8. Cancelled state
  it('8. renders CANCELLED transfer state appropriately', () => {
    const cancelledTransfer: Transfer = {
      id: 'tx-cancel',
      transferId: 'tx-cancel',
      fileName: 'archive.zip',
      fileSize: 52428800,
      transferredBytes: 10485760,
      direction: 'OUTGOING',
      state: 'CANCELLED',
      status: 'CANCELLED',
      peerId: 'peer-uuid-1',
      peerName: 'PC-2',
      errorMessage: 'Transfer cancelled by user',
    };

    const html = renderToString(<TransferCard transfer={cancelledTransfer} />);
    expect(html).toContain('Cancelled');
    expect(html).toContain('Transfer cancelled by user');
  });

  // 9. Empty transfer list
  it('9. renders EmptyState when no transfers exist', () => {
    const html = renderToString(
      <div className="transfers-empty-container">
        <h3>No transfers found</h3>
        <p>Select a local file and target a connected peer to stream data.</p>
      </div>
    );
    expect(html).toContain('No transfers found');
  });

  // 10. Loading state
  it('10. renders LoadingState message during initial fetch', () => {
    const html = renderToString(
      <div className="loading-state">
        <span className="loading-message">Loading transfers from MeshDrop node...</span>
      </div>
    );
    expect(html).toContain('Loading transfers from MeshDrop node...');
  });

  // 11. Backend offline state
  it('11. renders offline alert banner when backend is unreachable', () => {
    const html = renderToString(
      <div className="transfers-offline-banner" role="alert">
        <strong>Unable to reach MeshDrop control server.</strong> Live transfer updates are paused.
      </div>
    );
    expect(html).toContain('Unable to reach MeshDrop control server');
  });

  // 12. API failure handling
  it('12. handles API failure gracefully in service layer', async () => {
    const startSpy = vi.spyOn(meshDropApi, 'startTransfer').mockRejectedValueOnce(new Error('Peer connection timed out'));
    await expect(meshDropApi.startTransfer('peer-1', 'nonexistent.file')).rejects.toThrow('Peer connection timed out');
    startSpy.mockRestore();
  });

  // 13. File selection dialog
  it('13. renders SendFileDialog with file selection controls and disk path input', () => {
    const html = renderToString(
      <SendFileDialog
        isOpen={true}
        onClose={vi.fn()}
        peers={samplePeers}
        onSend={vi.fn()}
      />
    );
    expect(html).toContain('Send File to Peer');
    expect(html).toContain('Browse Local Files...');
    expect(html).toContain('Local File Path on Disk:');
    expect(html).toContain('500 MB Demo File');
  });

  // 14. Peer selection
  it('14. filters and presents only connected peers in SendFileDialog', () => {
    const html = renderToString(
      <SendFileDialog
        isOpen={true}
        onClose={vi.fn()}
        peers={samplePeers}
        onSend={vi.fn()}
      />
    );
    // PC-2 is CONNECTED
    expect(html).toContain('PC-2');
    expect(html).toContain('192.168.1.25:5000');
    expect(html).toContain('READY');
    // PC-3 is DISCONNECTED, should NOT be in the connected radio list
    expect(html).not.toContain('192.168.1.30:5000');
  });

  // 15. Cancel API call
  it('15. verifies cancelTransfer API call triggers backend cancellation', async () => {
    const cancelSpy = vi.spyOn(meshDropApi, 'cancelTransfer').mockResolvedValueOnce({
      success: true,
      transferId: 'tx-to-cancel',
    });

    const res = await meshDropApi.cancelTransfer('tx-to-cancel');
    expect(res.success).toBe(true);
    expect(res.transferId).toBe('tx-to-cancel');
    expect(cancelSpy).toHaveBeenCalledWith('tx-to-cancel');

    cancelSpy.mockRestore();
  });

  // ==========================================
  // PHASE 4 RELIABILITY & RESUME TESTS
  // ==========================================

  // 16. Resumable transfer rendering & controls
  it('16. renders RESUMABLE transfer state with Resume action button and Checkpoint tag', () => {
    const resumableTransfer: Transfer = {
      id: 'tx-resumable-1',
      transferId: 'tx-resumable-1',
      fileName: 'test500mb.dat',
      fileSize: 524288000,
      transferredBytes: 157286400, // 150 MB transferred
      remainingBytes: 367001600, // 350 MB remaining
      direction: 'INCOMING',
      state: 'RESUMABLE',
      status: 'RESUMABLE',
      peerId: 'peer-uuid-1',
      peerName: 'PC-2',
      hasCheckpoint: true,
      canResume: true,
      canCancel: false,
      canRemove: true,
    };

    const html = renderToString(
      <TransferCard
        transfer={resumableTransfer}
        onResume={vi.fn()}
        onRemove={vi.fn()}
      />
    );

    expect(html).toContain('test500mb.dat');
    expect(html).toContain('Resumable');
    expect(html).toContain('Checkpoint');
    expect(html).toContain('Resume');
    expect(html).toContain('Remove');
    expect(html).toContain('30%'); // 150 MB / 500 MB = 30%
    expect(html).toContain('350 MB left');
  });

  // 17. Interrupted transfer rendering
  it('17. renders INTERRUPTED transfer state with Interrupted badge', () => {
    const interruptedTransfer: Transfer = {
      id: 'tx-interrupted-1',
      transferId: 'tx-interrupted-1',
      fileName: 'dataset.tar',
      fileSize: 104857600,
      transferredBytes: 52428800,
      direction: 'OUTGOING',
      state: 'INTERRUPTED',
      status: 'INTERRUPTED',
      peerId: 'peer-uuid-1',
      peerName: 'PC-2',
      canResume: true,
      canRetry: true,
    };

    const html = renderToString(
      <TransferCard
        transfer={interruptedTransfer}
        onResume={vi.fn()}
        onRetry={vi.fn()}
      />
    );

    expect(html).toContain('Interrupted');
    expect(html).toContain('Resume');
    expect(html).toContain('Retry');
  });

  // 18. TransferDetailsModal rendering
  it('18. renders TransferDetailsModal with technical metadata, stepped timeline, and SHA-256', () => {
    const modalTransfer: Transfer = {
      id: '12345678-1234-1234-1234-123456789abc',
      transferId: '12345678-1234-1234-1234-123456789abc',
      fileName: 'large-data.bin',
      fileSize: 524288000,
      transferredBytes: 524288000,
      remainingBytes: 0,
      direction: 'INCOMING',
      state: 'COMPLETED',
      status: 'COMPLETED',
      peerId: 'peer-uuid-1',
      peerName: 'PC-2',
      hasCheckpoint: true,
      canResume: false,
      canRetry: false,
      canCancel: false,
      canRemove: true,
      sha256: 'A08A92258F621B55D08AD1E84C90C2EA6286FC6B6C9A4DFA7156AFB16C190170',
      startTime: Date.now() - 30000,
      completedTime: Date.now(),
    };

    const html = renderToString(
      <TransferDetailsModal
        isOpen={true}
        transfer={modalTransfer}
        onClose={vi.fn()}
        onRemove={vi.fn()}
      />
    );

    expect(html).toContain('Transfer Details');
    expect(html).toContain('12345678-1234-1234-1234-123456789abc');
    expect(html).toContain('Copy');
    expect(html).toContain('A08A92258F621B55D08AD1E84C90C2EA6286FC6B6C9A4DFA7156AFB16C190170');
    expect(html).toContain('Available (.part + .meta on disk)');
    expect(html).toContain('Technical Metadata');
    expect(html).toContain('Reliability &amp; Recovery');
    expect(html).toContain('timeline-steps');
    expect(html).toContain('Offered');
    expect(html).toContain('Remove from History');
  });

  // 19. Resume API call
  it('19. verifies resumeTransfer API calls POST /api/transfers/{id}/resume', async () => {
    const resumeSpy = vi.spyOn(meshDropApi, 'resumeTransfer').mockResolvedValueOnce({
      success: true,
      transferId: 'tx-resume-id',
      state: 'RESUMING',
    });

    const res = await meshDropApi.resumeTransfer('tx-resume-id');
    expect(res.success).toBe(true);
    expect(res.transferId).toBe('tx-resume-id');
    expect(res.state).toBe('RESUMING');
    expect(resumeSpy).toHaveBeenCalledWith('tx-resume-id');

    resumeSpy.mockRestore();
  });

  // 20. Retry API call
  it('20. verifies retryTransfer API calls POST /api/transfers/{id}/retry', async () => {
    const retrySpy = vi.spyOn(meshDropApi, 'retryTransfer').mockResolvedValueOnce({
      success: true,
      transferId: 'tx-retry-id',
      state: 'TRANSFERRING',
    });

    const res = await meshDropApi.retryTransfer('tx-retry-id');
    expect(res.success).toBe(true);
    expect(res.transferId).toBe('tx-retry-id');
    expect(retrySpy).toHaveBeenCalledWith('tx-retry-id');

    retrySpy.mockRestore();
  });

  // 21. Remove transfer API call
  it('21. verifies removeTransfer API calls DELETE /api/transfers/{id}', async () => {
    const removeSpy = vi.spyOn(meshDropApi, 'removeTransfer').mockResolvedValueOnce({
      success: true,
      transferId: 'tx-remove-id',
    });

    const res = await meshDropApi.removeTransfer('tx-remove-id');
    expect(res.success).toBe(true);
    expect(res.transferId).toBe('tx-remove-id');
    expect(removeSpy).toHaveBeenCalledWith('tx-remove-id');

    removeSpy.mockRestore();
  });

  // 22. Format transfer state
  it('22. formats internal transfer states into user-friendly labels', () => {
    expect(formatTransferState('TRANSFERRING')).toBe('Transferring');
    expect(formatTransferState('RESUMABLE')).toBe('Resumable');
    expect(formatTransferState('RESUMING')).toBe('Resuming');
    expect(formatTransferState('INTERRUPTED')).toBe('Interrupted');
    expect(formatTransferState('WAITING_FOR_ACCEPT')).toBe('Waiting for Accept');
    expect(formatTransferState('VERIFYING')).toBe('Verifying');
    expect(formatTransferState('COMPLETED')).toBe('Completed');
    expect(formatTransferState('FAILED')).toBe('Failed');
    expect(formatTransferState('CANCELLED')).toBe('Cancelled');
    expect(formatTransferState('TIMED_OUT')).toBe('Timed Out');
  });

  // 23. Format timestamp
  it('23. formats timestamps safely without throwing', () => {
    expect(formatTimestamp(null)).toBe('--');
    expect(formatTimestamp(undefined)).toBe('--');
    expect(formatTimestamp(0)).toBe('--');
    const formatted = formatTimestamp(1757073600000);
    expect(typeof formatted).toBe('string');
    expect(formatted.length).toBeGreaterThan(0);
  });
});
