import { Connection } from '../types/Connection';
import { NodeStatus } from '../types/Node';
import { Peer } from '../types/Peer';
import { Transfer } from '../types/Transfer';
import { apiClient } from './apiClient';

/**
 * Service boundary interface for the MeshDrop frontend.
 * Defines the contract for observation and safe control commands.
 */
export interface MeshDropApi {
  /**
   * Retrieves the local node's runtime identity, listening ports, and status.
   */
  getStatus(): Promise<NodeStatus>;

  /**
   * Retrieves all known (discovered, connecting, connected, disconnected) peers.
   */
  getPeers(): Promise<Peer[]>;

  /**
   * Retrieves all active raw TCP transport connections.
   */
  getConnections(): Promise<Connection[]>;

  /**
   * Retrieves active and recent transfers from the Java backend.
   */
  getTransfers(): Promise<Transfer[]>;

  /**
   * Retrieves detailed diagnostics for a single transfer.
   */
  getTransfer(transferId: string): Promise<Transfer>;

  /**
   * Initiates an outgoing file transfer to a connected peer.
   */
  startTransfer(peerId: string, filePath: string): Promise<{ success: boolean; transferId?: string; fileName?: string; fileSize?: number; state?: string; error?: string }>;

  /**
   * Resumes an interrupted or paused transfer.
   */
  resumeTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; state?: string; error?: string }>;

  /**
   * Retries a failed transfer where supported.
   */
  retryTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; state?: string; error?: string }>;

  /**
   * Cancels an active or interrupted transfer.
   */
  cancelTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; error?: string }>;

  /**
   * Removes a terminal transfer record from history (does not delete file on disk).
   */
  removeTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; error?: string }>;

  /**
   * Controlled interruption of a transfer (for reliability testing).
   */
  interruptTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; state?: string; error?: string }>;

  /**
   * Retrieves single peer details.
   */
  getPeer(peerId: string): Promise<Peer>;

  /**
   * Requests the local node to initiate an outbound TCP connection to a remote address.
   */
  connectPeer(host: string, port: number): Promise<{ success: boolean; connectionId?: number; error?: string }>;

  /**
   * Connects to a specific known peer by ID.
   */
  connectPeerById(peerId: string): Promise<{ success: boolean; connectionId?: number; peerId?: string; state?: string; error?: string }>;

  /**
   * Safely disconnects a peer by ID.
   */
  disconnectPeer(peerId: string): Promise<{ success: boolean; disconnected?: boolean; peerId?: string; state?: string; error?: string }>;
}

/**
 * Production implementation of the MeshDrop API boundary communicating
 * directly with the Java backend HTTP control layer.
 */
export class LiveMeshDropService implements MeshDropApi {
  async getStatus(): Promise<NodeStatus> {
    return apiClient.get<NodeStatus>('/api/status');
  }

  async getPeers(): Promise<Peer[]> {
    return apiClient.get<Peer[]>('/api/peers');
  }

  async getPeer(peerId: string): Promise<Peer> {
    return apiClient.get<Peer>(`/api/peers/${peerId}`);
  }

  async getConnections(): Promise<Connection[]> {
    return apiClient.get<Connection[]>('/api/connections');
  }

  async getTransfers(): Promise<Transfer[]> {
    return apiClient.get<Transfer[]>('/api/transfers');
  }

  async getTransfer(transferId: string): Promise<Transfer> {
    return apiClient.get<Transfer>(`/api/transfers/${transferId}`);
  }

  async startTransfer(peerId: string, filePath: string): Promise<{ success: boolean; transferId?: string; fileName?: string; fileSize?: number; state?: string; error?: string }> {
    return apiClient.post('/api/transfers', { peerId, filePath });
  }

  async resumeTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; state?: string; error?: string }> {
    return apiClient.post(`/api/transfers/${transferId}/resume`);
  }

  async retryTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; state?: string; error?: string }> {
    return apiClient.post(`/api/transfers/${transferId}/retry`);
  }

  async cancelTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; error?: string }> {
    return apiClient.post(`/api/transfers/${transferId}/cancel`, { transferId });
  }

  async removeTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; error?: string }> {
    return apiClient.delete(`/api/transfers/${transferId}`);
  }

  async interruptTransfer(transferId: string): Promise<{ success: boolean; transferId?: string; state?: string; error?: string }> {
    return apiClient.post(`/api/transfers/${transferId}/interrupt`);
  }

  async connectPeer(host: string, port: number): Promise<{ success: boolean; connectionId?: number; error?: string }> {
    return apiClient.post<{ success: boolean; connectionId?: number; error?: string }>('/api/connect', { host, port });
  }

  async connectPeerById(peerId: string): Promise<{ success: boolean; connectionId?: number; peerId?: string; state?: string; error?: string }> {
    return apiClient.post<{ success: boolean; connectionId?: number; peerId?: string; state?: string; error?: string }>(`/api/peers/${peerId}/connect`);
  }

  async disconnectPeer(peerId: string): Promise<{ success: boolean; disconnected?: boolean; peerId?: string; state?: string; error?: string }> {
    return apiClient.post<{ success: boolean; disconnected?: boolean; peerId?: string; state?: string; error?: string }>(`/api/peers/${peerId}/disconnect`);
  }
}

/**
 * Default live instance used across all hooks and UI components.
 */
export const meshDropApi: MeshDropApi = new LiveMeshDropService();
