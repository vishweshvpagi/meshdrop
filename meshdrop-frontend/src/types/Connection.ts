export type ConnectionState =
  | 'CONNECTED'
  | 'HANDSHAKING'
  | 'READY'
  | 'CLOSING'
  | 'CLOSED'
  | 'UNKNOWN';

export type ConnectionDirection = 'INBOUND' | 'OUTBOUND' | 'UNKNOWN';

export interface Connection {
  connectionId: number;
  peerId: string | null;
  displayName: string | null;
  state: ConnectionState;
  direction: ConnectionDirection;
  remoteAddress: string;
  connectedAt: number;
  durationMillis: number;
}
