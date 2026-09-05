export type PeerState = 'DISCOVERED' | 'CONNECTING' | 'CONNECTED' | 'DISCONNECTED';

export interface Peer {
  id: string;
  displayName: string;
  address: string;
  port: number;
  state: PeerState;
  connected: boolean;
  lastSeen: string | null;
  connectedAt: string | null;
  fingerprint: string;
  trustDecision: string;
}
