import { Connection } from '../types/Connection';
import { NodeStatus } from '../types/Node';
import { Peer } from '../types/Peer';
import { Transfer } from '../types/Transfer';

export const mockCurrentNode: NodeStatus = {
  nodeId: 'a4c8-91df-4b2a-7e10',
  displayName: 'PC-1',
  running: true,
  state: 'RUNNING',
  tcpPort: 5000,
  discoveryPort: 5001,
  discoveryRunning: true,
  fingerprint: '3F8B:C74A:92E1:5D03:7A19:44CE:B820:F194',
  uptimeMillis: 4325000,
  connectionCount: 2,
  peerCount: 3,
};

export const mockPeers: Peer[] = [
  {
    id: 'b712-44fa-9901-22cd',
    displayName: 'PC-2',
    address: '192.168.1.20',
    port: 5000,
    state: 'CONNECTED',
    connected: true,
    lastSeen: '2026-09-05T13:20:00Z',
    connectedAt: '2026-09-05T12:00:00Z',
    fingerprint: '8A91:3C2F:D410:5B82:61AE:39FF:08CD:A14B',
    trustDecision: 'TRUSTED',
  },
  {
    id: 'e309-8812-7104-cc12',
    displayName: 'Laptop',
    address: '192.168.1.35',
    port: 5000,
    state: 'DISCONNECTED',
    connected: false,
    lastSeen: '2026-09-05T13:05:00Z',
    connectedAt: null,
    fingerprint: '11DF:90BB:64EA:2310:7CDE:550A:8892:99AC',
    trustDecision: 'UNTRUSTED',
  },
  {
    id: 'fa80-1928-3344-9988',
    displayName: 'Home-Server',
    address: '192.168.1.100',
    port: 5000,
    state: 'CONNECTED',
    connected: true,
    lastSeen: '2026-09-05T13:22:00Z',
    connectedAt: '2026-09-05T12:15:00Z',
    fingerprint: '44FE:2231:90A1:CC09:1288:99AE:DDBB:0102',
    trustDecision: 'TRUSTED',
  },
];

export const mockConnections: Connection[] = [
  {
    connectionId: 101,
    peerId: 'b712-44fa-9901-22cd',
    displayName: 'PC-2',
    state: 'READY',
    direction: 'OUTBOUND',
    remoteAddress: '/192.168.1.20:5000',
    connectedAt: Date.now() - 4920000,
    durationMillis: 4920000,
  },
  {
    connectionId: 102,
    peerId: 'fa80-1928-3344-9988',
    displayName: 'Home-Server',
    state: 'READY',
    direction: 'INBOUND',
    remoteAddress: '/192.168.1.100:54321',
    connectedAt: Date.now() - 4020000,
    durationMillis: 4020000,
  },
];

export const mockTransfers: Transfer[] = [
  {
    id: 'tx-001',
    fileName: 'ubuntu-24.04-desktop-amd64.iso',
    fileSize: 4509715660,
    transferredBytes: 3246995275,
    peerId: 'b712-44fa-9901-22cd',
    peerName: 'PC-2',
    direction: 'UPLOAD',
    status: 'TRANSFERRING',
    speed: 34603008,
    eta: 36,
    startedAt: '2026-09-05T13:02:10Z',
  },
];
