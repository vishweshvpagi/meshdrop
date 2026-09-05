export interface NodeStatus {
  nodeId: string;
  displayName: string;
  running: boolean;
  state: string; // INITIALIZING, RUNNING, SHUTTING_DOWN, STOPPED
  tcpPort: number;
  discoveryPort: number;
  discoveryRunning: boolean;
  fingerprint: string;
  uptimeMillis: number;
  connectionCount: number;
  peerCount: number;
}
