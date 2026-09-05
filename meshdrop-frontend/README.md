# MeshDrop Frontend

> Desktop control panel for MeshDrop peer-to-peer file and message transfers.

## 1. Overview

MeshDrop Frontend provides a lightweight graphical user interface for observing and controlling the MeshDrop decentralized P2P network. Designed with the clean, minimalist aesthetic of a desktop networking utility, it presents real-time peer discovery, active TCP connections, local node identity, and transfer queues without visual clutter or heavy third-party framework overhead.

---

## 2. Architecture & Communication

The application separates concerns strictly between UI presentation and core networking:

```text
┌──────────────────────────────────────────────┐
│           React Desktop Frontend             │
│   (useTheme, useNodeStatus, usePeers, etc.)  │
└──────────────────────┬───────────────────────┘
                       │ HTTP / REST (Polling 2.5s)
                       ▼
┌──────────────────────────────────────────────┐
│       MeshDrop Java HTTP Control API         │
│         (com.meshdrop.api on :8080)          │
└──────────────────────┬───────────────────────┘
                       │ In-Process Virtual Thread Calls
                       ▼
┌──────────────────────────────────────────────┐
│          MeshDrop Core Engine                │
│    Node, PeerManager, ConnectionManager      │
│     (Port 5000 TCP / Port 5001 UDP Discovery)│
└──────────────────────────────────────────────┘
```

---

## 3. Configuration & Environment Variables

The frontend connects to the MeshDrop backend using `VITE_MESHDROP_API_URL`:

| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `VITE_MESHDROP_API_URL` | `http://localhost:8080` | Base URL of the MeshDrop Java control API |

To customize for development, create a `.env` or `.env.local` file:
```env
VITE_MESHDROP_API_URL=http://localhost:8080
```

---

## 4. Light / Dark Theme System

- **Genuine Dark Theme**: Crafted for a classic developer/networking tool aesthetic with charcoal/slate surfaces (`#0f172a`, `#1e293b`), crisp borders, and restrained accents.
- **Top Bar Toggle**: Click the theme toggle (`Light` / `Dark`) in the top bar to switch immediately.
- **System Preference**: Defaults to system OS theme via `window.matchMedia('(prefers-color-scheme: dark)')`.
- **Persistence**: Saved across sessions in `localStorage` (`meshdrop-theme`).
- **No Flash of Light Theme**: An inline anti-flash script in `index.html` applies the theme before initial DOM render.

---

## 5. File Transfers, Reliability & Resume Architecture (Phases 3 & 4)

### 5.1 Architecture & Memory Safety
MeshDrop supports multi-gigabyte files (500 MB, 1 GB, 4 GB, 10 GB+) without loading file bytes into browser memory:
- **Zero JavaScript Heap Overhead**: `<input type="file">` is used exclusively for extracting metadata (`fileName`, `fileSize`, `fileType`). React never invokes `arrayBuffer()`, `FileReader`, or Base64 encoding.
- **Direct Java Engine Streaming**: The UI transmits `{ peerId, filePath }` to `POST /api/transfers`. The Java engine opens the file directly from disk and streams it in 64 KiB chunks with a bounded sliding window ($O(\text{chunkSize} \times \text{windowSize})$ memory footprint, ~512 KiB–2 MiB heap space).
- **Adaptive Polling**: `useTransfers` dynamically adjusts polling intervals — **1.5 seconds** when transfers are active or resuming, back to **4.0 seconds** when transfers are idle or completed, minimizing CPU and network overhead.
- **Authoritative Progress & Capability Flags**: Progress is derived strictly from real transferred bytes on disk: $\text{progress} = \text{transferredBytes} / \text{fileSize}$. Capabilities (`canResume`, `canCancel`, `canRetry`, `canRemove`, `hasCheckpoint`, `remainingBytes`) are evaluated authoritatively by the Java backend — no fake timers or client-side offset guesswork exist.

### 5.2 Supported Transfer States & Lifecycle
- `WAITING_FOR_ACCEPT`: Transfer proposal dispatched, awaiting remote peer acceptance.
- `ACCEPTED`: Peer accepted the transfer; preparing chunk stream and socket pipeline.
- `TRANSFERRING`: Active chunk streaming with live byte counts, instantaneous speed, and dynamic ETA calculation.
- `RESUMABLE` / `INTERRUPTED`: Checkpoint (.meta + .part) saved on disk. Can be resumed seamlessly from last acknowledged chunk.
- `RESUMING`: Checkpoint verified; resuming streaming from byte offset without resetting to 0.
- `VERIFYING`: All chunks received; calculating local SHA-256 hash against metadata digest.
- `COMPLETED`: Transfer verified and written safely to final disk destination.
- `FAILED`: Transfer encountered an unrecoverable network or disk I/O error.
- `CANCELLED`: Transfer aborted by user with checkpoint cleanup.
- `REJECTED`: Remote peer rejected the file offer.
- `TIMED_OUT`: Peer failed to respond within connection timeout window.

### 5.3 Transfer Controls & Inspection
- **Resume Transfer (`POST /api/transfers/{id}/resume`)**: Resumes an interrupted/resumable transfer directly from its saved checkpoint offset without restarting from byte 0.
- **Retry Transfer (`POST /api/transfers/{id}/retry`)**: Restarts an outbound failed or cancelled transfer from scratch (byte 0).
- **Cancel Transfer (`POST /api/transfers/{id}/cancel`)**: Aborts the active transfer, terminates the TCP socket, and cleans up temporary checkpoint artifacts.
- **Remove from History (`DELETE /api/transfers/{id}`)**: Clears terminal transfer records (`COMPLETED`, `FAILED`, `CANCELLED`, `REJECTED`, `TIMED_OUT`) from memory history while keeping downloaded files intact on disk.
- **Transfer Details Modal**: In-depth inspection modal featuring:
  - Visual 5-step status timeline (`Offered` → `Accepted` → `Transferring` / `Interrupted` → `Verifying` → `Completed`).
  - Stat cards: Transfer Speed, ETA, Remaining Bytes, and Duration.
  - Checkpoint integrity status badge.
  - Copyable Transfer UUID, Peer UUID, and SHA-256 checksum digest.
  - Inline action buttons with safety confirmations.

---

## 6. Application Integration, Peer Controls & Production Hardening (Phase 5)

### 6.1 Safe Peer Connection Controls
- **Direct Connect / Disconnect**: Peers page and Peer Details modal expose contextual controls:
  - `[ Connect ]`: Manually connects to discovered or disconnected peers over TCP via `POST /api/peers/{id}/connect`.
  - `[ Disconnect ]`: Safely closes the active TCP session with `POST /api/peers/{id}/disconnect`.
- **Active Transfer Safety Warning**: When disconnecting a peer that currently has active file transfers in progress, the UI displays a confirmation alert dialog warning the user that transfers will be safely halted into on-disk checkpoints (`.part` + `.meta`) rather than silently corrupted.
- **Peer Details Inspection Modal**:
  - Displays local trust evaluation (`TRUSTED` / `UNTRUSTED`).
  - Full cryptographic Ed25519 fingerprint with 1-click clipboard copy.
  - Resolved network address, port, and live TCP socket duration.
  - Filtered list of all active, resumable, and completed file transfers specifically associated with that peer.
  - Contextual action buttons (`Connect`, `Disconnect`, `Send File`).

### 6.2 Global Connection Resilience & Auto-Recovery
- **Subtle Global Error Banner**: When the backend process goes down or restarts, a top banner (`MeshDrop backend is unavailable. Trying to reconnect...`) appears without unmounting the application.
- **Automatic Recovery**: The application polls with backoff; as soon as the Java node restarts, the banner transitions to `Reconnected to MeshDrop engine` and dismisses itself, seamlessly restoring live state.
- **Stale Data Indicators**: If cached data is visible while offline, timestamp indicators show `Offline • Last updated X ago`.

### 6.3 Authoritative Recent Activity Feed
- **Authoritative Event Derivation**: Zero simulated or fake activity. The Dashboard dynamically computes recent activity by chronologically ordering real transfer events (completed, started, interrupted) and peer lifecycle events (connected, discovered, disconnected).
- **Interactive Links**: Transfer events in the activity feed allow 1-click inspection via the Transfer Details Modal.

### 6.4 Transfers UI Polish & Action Guards
- **Standardized Confirmation Dialogs**: Cancel and Remove actions use accessible modal dialogs (`<ConfirmationDialog>`) instead of shifting inline card layouts.
- **Live Action Busy States**: Buttons transition into explicit loading feedback (`Resuming...`, `Cancelling...`, `Retrying...`, `Removing...`) and lock out concurrent conflicting actions during in-flight operations.
- **Page Visibility Optimization**: Polling is throttled when the browser tab is hidden (`document.hidden`), and an immediate authoritative refresh fires upon tab focus (`visibilitychange`).
- **Non-Intrusive Toast Alerts**: Toast alerts appear in the bottom-right corner for meaningful lifecycle events (peer connected, peer disconnected, connection failed, etc.).

---

## 7. Starting the Applications

### 7.1 Starting the Java Backend

From `SocketStuff/`:
```powershell
# Build the Java backend
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1

# Run Node on default ports (TCP: 5000, Discovery UDP: 5001, HTTP API: 8080)
java -cp out com.meshdrop.Main --name PC-1 --tcp-port 5000 --udp-port 5001 --api-port 8080
```

### 7.2 Starting the React Frontend

From `meshdropfrontend/`:
```bash
# Install dependencies
npm install

# Start Vite development server on port 3000
npm run dev

# Run automated tests (Vitest)
npm test

# Build production bundle
npm run build
```

Open `http://localhost:3000` in your web browser.

---

## 8. Running a Two-Node Network Test

To verify mutual peer discovery, TCP handshake, peer disconnect/reconnect, and live frontend updates:

1. **Start Node A**:
   ```powershell
   java -cp out com.meshdrop.Main --name PC-1 --tcp-port 5000 --udp-port 5001 --api-port 8080 --data-dir data/nodeA
   ```
2. **Start Node B** (in another terminal):
   ```powershell
   java -cp out com.meshdrop.Main --name PC-2 --tcp-port 5002 --udp-port 5001 --api-port 8081 --data-dir data/nodeB
   ```
3. **Observe**:
   - Both nodes exchange UDP multicast discovery packets.
   - Outbound TCP handshake completes and verifies Ed25519 identity.
   - Open `http://localhost:3000`:
     - Dashboard displays `Node: PC-1 (Online)`.
     - `PC-2` appears in the Known Peers list with `CONNECTED` state.
     - Active TCP connection list displays the socket to `PC-2`.
     - Recent Activity reflects peer discovery and connection events.
     - Sending a 500 MB file streams at 100+ MB/s and verifies SHA-256 integrity.
     - Disconnecting `PC-2` safely halts active transfers into resumable checkpoints, which resume seamlessly upon reconnection.

---

## 9. Troubleshooting Backend Connection Problems

If the frontend displays `● Offline` or `MeshDrop backend is unreachable`:

1. **Verify Backend Process**: Ensure the Java node is running and `[API] MeshDrop Control API listening on http://127.0.0.1:8080` is logged.
2. **Port Conflict**: Check if port 8080 is in use by another service. Specify another port (e.g. `--api-port 8085`) and update `VITE_MESHDROP_API_URL=http://localhost:8085`.
3. **CORS Configuration**: The backend permits requests from `http://localhost:3000`, `http://127.0.0.1:3000`, `http://localhost:5173`, and `http://127.0.0.1:5173`. Ensure your dev server matches one of these origins.
4. **Firewall Rules**: On Windows, ensure local loopback connections are not blocked.

---

## 10. Release Readiness & Quality Assurance Matrix (Phase 6)

MeshDrop and its React control panel have completed end-to-end integration and rigorous QA validation.

### 10.1 Automated Verification Suites
- **Backend Test Suite**: **81 passed, 0 failed** (`powershell -ExecutionPolicy Bypass -File scripts/test.ps1`). Covers packet codecs, sliding-window flow control, cryptographic fingerprints, UDP multicast discovery, HTTP Control API contracts, checkpoint persistence, and interrupted transfer resumption.
- **Frontend Test Suite**: **42 passed, 0 failed** (`npm test`). Covers theme toggling/anti-flash, API client normalizations, peer connection guards, transfer state machines, action dialogs, and timeline rendering.
- **Production Build**: Compiles cleanly with zero warnings or errors via `npm run build` (`tsc -b && vite build`) producing a 74 KB gzip bundle.

### 10.2 Transfer Performance & Reliability Benchmarks
- **500 MB Large File Transfer**: Streamed across nodes in **4.9 seconds** (~106 MB/s average throughput on loopback). Completed with **100% bit-for-bit SHA-256 verification**.
- **Crash & Interruption Recovery**: Transfers paused mid-stream persist on-disk checkpoints (`.part` + `.meta`). Resuming requests cleanly continue from the receiver's last contiguous chunk without re-transmitting previous data.
- **Backend Lifecycle Recovery**: If the Java backend restarts, the frontend non-destructively notifies the user via an unobtrusive top banner and automatically re-establishes synchronization upon backend availability.

