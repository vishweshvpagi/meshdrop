import { describe, expect, it, vi } from 'vitest';
import { renderToString } from 'react-dom/server';
import { ConfirmationDialog } from '../components/common/ConfirmationDialog';
import { PeerDetailsModal } from '../components/peers/PeerDetailsModal';
import { TransferCard } from '../components/transfers/TransferCard';
import { meshDropApi } from '../services/meshdropApi';
import { Peer } from '../types/Peer';
import { Transfer } from '../types/Transfer';
import { formatRelativeTime } from '../utils/formatters';

describe('Phase 5 Peer Management & Integration Test Suite', () => {
  const samplePeer: Peer = {
    id: 'peer-uuid-1',
    displayName: 'Node-Beta',
    address: '192.168.1.55',
    port: 5002,
    state: 'CONNECTED',
    connected: true,
    trustDecision: 'TRUSTED',
    lastSeen: '2026-09-05T12:00:00Z',
    connectedAt: '2026-09-05T12:00:00Z',
    fingerprint: 'A1B2-C3D4-E5F6-7890',
  };

  const sampleDisconnectedPeer: Peer = {
    id: 'peer-uuid-2',
    displayName: 'Node-Gamma',
    address: '192.168.1.60',
    port: 5004,
    state: 'DISCONNECTED',
    connected: false,
    trustDecision: 'UNTRUSTED',
    lastSeen: '2026-09-05T11:45:00Z',
    connectedAt: null,
    fingerprint: 'F0E1-D2C3-B4A5-6789',
  };

  const sampleTransfers: Transfer[] = [
    {
      id: 'tx-1',
      transferId: 'tx-1',
      fileName: 'dataset.zip',
      fileSize: 104857600, // 100 MB
      transferredBytes: 52428800, // 50 MB
      direction: 'OUTGOING',
      state: 'TRANSFERRING',
      status: 'TRANSFERRING',
      peerId: 'peer-uuid-1',
      peerName: 'Node-Beta',
      speedBytesPerSecond: 10485760,
      etaSeconds: 5,
    },
    {
      id: 'tx-2',
      transferId: 'tx-2',
      fileName: 'manual.pdf',
      fileSize: 2097152, // 2 MB
      transferredBytes: 2097152,
      direction: 'INCOMING',
      state: 'COMPLETED',
      status: 'COMPLETED',
      peerId: 'peer-uuid-1',
      peerName: 'Node-Beta',
      completedTime: Date.now() - 60000,
    },
  ];

  // 1. Peer Details Modal rendering
  it('1. renders PeerDetailsModal with peer identity, network address, and fingerprint', () => {
    const html = renderToString(
      <PeerDetailsModal
        isOpen={true}
        peer={samplePeer}
        transfers={sampleTransfers}
        onClose={() => {}}
        onSendFile={() => {}}
        onDisconnect={() => {}}
      />
    );

    expect(html).toContain('Node-Beta');
    expect(html).toContain('CONNECTED');
    expect(html).toContain('192.168.1.55:5002');
    expect(html).toContain('A1B2-C3D4-E5F6-7890');
    expect(html).toContain('dataset.zip');
    expect(html).toContain('manual.pdf');
    expect(html).toContain('Disconnect');
    expect(html).toContain('Send File');
  });

  it('2. renders PeerDetailsModal for disconnected peer with Connect action', () => {
    const html = renderToString(
      <PeerDetailsModal
        isOpen={true}
        peer={sampleDisconnectedPeer}
        transfers={[]}
        onClose={() => {}}
        onConnect={() => {}}
      />
    );

    expect(html).toContain('Node-Gamma');
    expect(html).toContain('DISCONNECTED');
    expect(html).toContain('192.168.1.60:5004');
    expect(html).toContain('Connect');
  });

  // 3. Confirmation Dialog behavior
  it('3. renders ConfirmationDialog with title, message, and action buttons', () => {
    const html = renderToString(
      <ConfirmationDialog
        isOpen={true}
        title="Confirm Disconnect"
        message="Active transfers will be halted into checkpoints."
        confirmLabel="Disconnect Now"
        cancelLabel="Keep Connection"
        confirmVariant="danger"
        onConfirm={() => {}}
        onCancel={() => {}}
      />
    );

    expect(html).toContain('Confirm Disconnect');
    expect(html).toContain('Active transfers will be halted into checkpoints.');
    expect(html).toContain('Disconnect Now');
    expect(html).toContain('Keep Connection');
  });

  it('4. hides ConfirmationDialog when isOpen is false', () => {
    const html = renderToString(
      <ConfirmationDialog
        isOpen={false}
        title="Hidden Dialog"
        message="Should not render"
        onConfirm={() => {}}
        onCancel={() => {}}
      />
    );

    expect(html).toBe('');
  });

  // 5. TransferCard Phase 5 Confirmation dialog integration
  it('5. renders TransferCard with confirmation modals ready', () => {
    const transfer: Transfer = {
      id: 'tx-test-resumable',
      transferId: 'tx-test-resumable',
      fileName: 'backup.tar.gz',
      fileSize: 524288000,
      transferredBytes: 262144000,
      direction: 'INCOMING',
      state: 'RESUMABLE',
      status: 'RESUMABLE',
      peerId: 'peer-uuid-1',
      peerName: 'Node-Beta',
      hasCheckpoint: true,
      canResume: true,
    };

    const html = renderToString(
      <TransferCard
        transfer={transfer}
        onResume={() => {}}
        onCancel={() => {}}
        onRemove={() => {}}
      />
    );

    expect(html).toContain('backup.tar.gz');
    expect(html).toContain('Resumable');
    expect(html).toContain('Checkpoint');
    expect(html).toContain('Resume');
  });

  // 6. formatRelativeTime helper tests
  it('6. formats relative time strings properly', () => {
    const now = Date.now();
    expect(formatRelativeTime(now - 1000)).toBe('just now');
    expect(formatRelativeTime(now - 15000)).toBe('15s ago');
    expect(formatRelativeTime(now - 120000)).toBe('2m ago');
    expect(formatRelativeTime(now - 7200000)).toBe('2h ago');
    expect(formatRelativeTime(null)).toBe('');
  });

  // 7. API Client peer management calls
  it('7. calls meshDropApi peer endpoints correctly', async () => {
    const originalFetch = globalThis.fetch;
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/peers/peer-123/connect')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ success: true, connectionId: 42, peerId: 'peer-123' }),
        });
      }
      if (url.includes('/api/peers/peer-123/disconnect')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ success: true, message: 'Peer disconnected' }),
        });
      }
      if (url.includes('/api/peers/peer-123')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve(samplePeer),
        });
      }
      return Promise.reject(new Error('Unknown URL: ' + url));
    });

    globalThis.fetch = mockFetch;

    try {
      // Test getPeer
      const peerRes = await meshDropApi.getPeer('peer-123');
      expect(peerRes).toEqual(samplePeer);

      // Test connectPeerById
      const connectRes = await meshDropApi.connectPeerById('peer-123');
      expect(connectRes.success).toBe(true);
      expect(connectRes.connectionId).toBe(42);

      // Test disconnectPeer
      const disconnectRes = await meshDropApi.disconnectPeer('peer-123');
      expect(disconnectRes.success).toBe(true);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});
