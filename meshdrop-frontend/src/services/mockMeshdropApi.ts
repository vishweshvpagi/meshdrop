import { Connection } from '../types/Connection';
import { NodeStatus } from '../types/Node';
import { Peer } from '../types/Peer';
import { Transfer } from '../types/Transfer';
import { mockConnections, mockCurrentNode, mockPeers, mockTransfers } from '../utils/mockData';
import { MeshDropApi } from './meshdropApi';

/**
 * Isolated Mock Implementation of the MeshDrop API.
 * Used exclusively for local offline testing and demo sandboxes.
 * Never mixed with live network operations.
 */
export class MockMeshDropService implements MeshDropApi {
  async getStatus(): Promise<NodeStatus> {
    return Promise.resolve({ ...mockCurrentNode });
  }

  async getPeers(): Promise<Peer[]> {
    return Promise.resolve([...mockPeers]);
  }

  async getConnections(): Promise<Connection[]> {
    return Promise.resolve([...mockConnections]);
  }

  async getTransfers(): Promise<Transfer[]> {
    return Promise.resolve([...mockTransfers]);
  }

  async connectPeer(_host: string, _port: number): Promise<{ success: boolean; connectionId?: number; error?: string }> {
    return Promise.resolve({
      success: true,
      connectionId: Math.floor(Math.random() * 1000) + 1,
    });
  }

  async startTransfer(_peerId: string, filePath: string): Promise<{ success: boolean; transferId?: string; fileName?: string; fileSize?: number; state?: string; error?: string }> {
    const fileName = filePath.split(/[/\\]/).pop() || 'file.bin';
    return Promise.resolve({
      success: true,
      transferId: 'mock-tx-' + Date.now(),
      fileName,
      fileSize: 1048576,
      state: 'WAITING_FOR_ACCEPT',
    });
  }

  async getTransfer(transferId: string): Promise<Transfer> {
    const found = mockTransfers.find((t) => t.id === transferId || t.transferId === transferId);
    if (!found) {
      throw new Error(`Transfer not found: ${transferId}`);
    }
    return Promise.resolve({ ...found });
  }

  async resumeTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; state?: string; error?: string }> {
    return Promise.resolve({
      success: true,
      transferId,
      state: 'RESUMING',
    });
  }

  async retryTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; state?: string; error?: string }> {
    return Promise.resolve({
      success: true,
      transferId,
      state: 'RESUMING',
    });
  }

  async cancelTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; error?: string }> {
    return Promise.resolve({
      success: true,
      transferId,
    });
  }

  async removeTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; error?: string }> {
    return Promise.resolve({
      success: true,
      transferId,
    });
  }

  async interruptTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; state?: string; error?: string }> {
    return Promise.resolve({
      success: true,
      transferId,
      state: 'RESUMABLE',
    });
  }

  async getPeer(peerId: string): Promise<Peer> {
    const peer = mockPeers.find((p) => p.id === peerId);
    if (!peer) throw new Error('Peer not found');
    return Promise.resolve(peer);
  }

  async connectPeerById(peerId: string): Promise<{ success: boolean; connectionId?: number; peerId?: string; state?: string; error?: string }> {
    return Promise.resolve({
      success: true,
      connectionId: 1,
      peerId,
      state: 'CONNECTED',
    });
  }

  async disconnectPeer(peerId: string): Promise<{ success: boolean; disconnected?: boolean; peerId?: string; state?: string; error?: string }> {
    return Promise.resolve({
      success: true,
      disconnected: true,
      peerId,
      state: 'DISCONNECTED',
    });
  }
}
