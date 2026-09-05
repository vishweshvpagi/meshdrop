# MeshDrop Control API Specification

The MeshDrop Control API is a local, lightweight HTTP service built directly into the Java core node using `com.sun.net.httpserver.HttpServer` (Java standard library, zero external dependencies). It acts as a decoupled observation and safe control layer for desktop frontend control panels (such as the React frontend in `meshdrop-frontend`).

---

## 1. Server Configuration

- **Default Port**: `8080` (TCP)
- **Configurable Flag**: `--api-port <port>` (passed to `com.meshdrop.Main`)
- **Disable API**: Pass `--api-port 0` or `--no-api`
- **Threading Model**: Driven by Java Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`)
- **CORS Support**: Permitted for `http://localhost:3000`, `http://127.0.0.1:3000`, `http://localhost:5173`, `http://127.0.0.1:5173` with automatic preflight `OPTIONS` handling (returns HTTP 204) and `DELETE` support.

---

## 2. API Endpoints

### 2.1 `GET /api/status`
Retrieves local node identity, runtime configuration, listening ports, uptime, and high-level connectivity counters.

#### Response: `200 OK`
```json
{
  "nodeId": "e763a6ab-2f80-4b2e-849e-943a01206e37",
  "displayName": "PC-1",
  "running": true,
  "state": "RUNNING",
  "tcpPort": 5000,
  "discoveryPort": 5001,
  "discoveryRunning": true,
  "fingerprint": "3F8B:C74A:92E1:5D03:7A19:44CE:B820:F194",
  "uptimeMillis": 124500,
  "connectionCount": 1,
  "peerCount": 1
}
```

---

### 2.2 `GET /api/peers`
Returns all remote peers discovered via UDP multicast announcements, manual connections, or incoming handshakes.

#### Response: `200 OK`
```json
[
  {
    "id": "52cc2aa0-b464-4b95-8cde-24524d94c4ce",
    "displayName": "PC-2",
    "address": "192.168.1.20",
    "port": 5000,
    "state": "CONNECTED",
    "connected": true,
    "lastSeen": "2026-09-05T13:20:00Z",
    "connectedAt": "2026-09-05T13:18:30Z",
    "fingerprint": "8A91:3C2F:D410:5B82:61AE:39FF:08CD:A14B",
    "trustDecision": "TRUSTED"
  }
]
```

#### Peer States:
- `DISCOVERED`: Announced on local UDP multicast network, but no TCP session established yet.
- `CONNECTING`: Outbound TCP connection or mutual handshake currently negotiating.
- `CONNECTED`: Mutual Ed25519 identity verified; session ready for bidirectional exchange.
- `DISCONNECTED`: Previously active session disconnected or timed out.

---

### 2.3 `GET /api/connections`
Returns active raw TCP transport sockets registered in `ConnectionManager` and `Node`.

#### Response: `200 OK`
```json
[
  {
    "connectionId": 185,
    "peerId": "52cc2aa0-b464-4b95-8cde-24524d94c4ce",
    "displayName": "PC-2",
    "state": "READY",
    "direction": "OUTBOUND",
    "remoteAddress": "/192.168.1.20:5000",
    "connectedAt": 1788614400000,
    "durationMillis": 64320
  }
]
```

---

### 2.4 `POST /api/connect`
Requests the local node to initiate an outbound TCP connection to an explicit host and port.

#### Request Body
```json
{
  "host": "192.168.1.25",
  "port": 5000
}
```

#### Response: `200 OK`
```json
{
  "success": true,
  "connectionId": 186
}
```

---

### 2.5 `GET /api/transfers`
Returns all active and historical file transfers with authoritative reliability capabilities and remaining byte metrics.

#### Response: `200 OK`
```json
[
  {
    "transferId": "4a15ef82-b36d-4919-9061-68939c4a5c0b",
    "fileName": "test500mb.dat",
    "fileSize": 524288000,
    "transferredBytes": 270729216,
    "remainingBytes": 253558784,
    "direction": "OUTGOING",
    "peerId": "52cc2aa0-b464-4b95-8cde-24524d94c4ce",
    "peerName": "PC-2",
    "state": "RESUMABLE",
    "status": "RESUMABLE",
    "speedBytesPerSecond": 0.0,
    "etaSeconds": -1,
    "progressPercentage": 51.6,
    "errorMessage": "Transfer interrupted",
    "startTime": 1788614400000,
    "completedTime": 0,
    "sha256": "a08a92258f621b55d08ad1e84c90c2ea6286fc6b6c9a4dfa7156afb16c190170",
    "canResume": true,
    "canCancel": false,
    "canRetry": false,
    "canRemove": true,
    "hasCheckpoint": true
  }
]
```

---

### 2.6 `GET /api/transfers/{id}`
Returns granular technical metadata, reliability checkpoint status, and capability flags for a specific transfer UUID.

#### Response: `200 OK`
Returns single transfer object matching schema in 2.5.

---

### 2.7 `POST /api/transfers`
Initiates an outgoing streaming file transfer from disk to a target peer.

#### Request Body
```json
{
  "peerId": "52cc2aa0-b464-4b95-8cde-24524d94c4ce",
  "filePath": "C:\\Users\\VBP\\Desktop\\SocketStuff\\data\\test500mb.dat"
}
```

---

### 2.8 `POST /api/transfers/{id}/resume`
Resumes an interrupted or resumable transfer job using on-disk `.part` and `.meta` checkpoints. Resumes directly from the verified offset without re-transferring previously received data.

#### Response: `200 OK`
```json
{
  "success": true,
  "transferId": "4a15ef82-b36d-4919-9061-68939c4a5c0b",
  "state": "RESUMING"
}
```

---

### 2.9 `POST /api/transfers/{id}/cancel`
Cancels an active or interrupted transfer, notifies peers, and transitions to `CANCELLED`. Backward-compatible with legacy `POST /api/transfers/cancel`.

#### Response: `200 OK`
```json
{
  "success": true,
  "transferId": "4a15ef82-b36d-4919-9061-68939c4a5c0b"
}
```

---

### 2.10 `POST /api/transfers/{id}/retry`
Restarts an outbound failed or timed-out file transfer from the beginning if local source file exists.

#### Response: `200 OK`
```json
{
  "success": true,
  "transferId": "4a15ef82-b36d-4919-9061-68939c4a5c0b",
  "state": "TRANSFERRING"
}
```

---

### 2.11 `POST /api/transfers/{id}/interrupt`
Deterministically pauses/interrupts an active transfer, writes on-disk `.part` and `.meta` checkpoints, and severs transport socket for reliability verification.

#### Response: `200 OK`
```json
{
  "success": true,
  "transferId": "4a15ef82-b36d-4919-9061-68939c4a5c0b",
  "state": "FAILED"
}
```

---

### 2.12 `DELETE /api/transfers/{id}`
Removes a terminal transfer (`COMPLETED`, `CANCELLED`, `FAILED`, `REJECTED`) from active node memory registry. Completed downloaded files on disk remain strictly intact.

#### Response: `200 OK`
```json
{
  "success": true,
  "transferId": "4a15ef82-b36d-4919-9061-68939c4a5c0b"
}
```

---

## 3. Reliability & Recovery Architecture

1. **Staging & Checkpoints**: In-flight downloads write to `.transfer-<uuid>.part` staging files and `.transfer-<uuid>.meta` atomic JSON metadata checkpoints in `tempDir`.
2. **Crash Recovery**: On restart, `scanAndRegisterRecoverableTransfers()` inspects `tempDir` and registers recoverable transfers as `RESUMABLE` without auto-starting.
3. **Bit-for-Bit Verification**: Full-file SHA-256 is verified after the final chunk. Staging artifacts are cleanly deleted upon success.
4. **Authoritative State**: Capability flags (`canResume`, `canCancel`, `canRetry`, `canRemove`) are calculated by the Java backend based on true disk and state invariants.
