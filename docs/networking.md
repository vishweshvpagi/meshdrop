# MeshDrop Networking Architecture

## 1. Transport vs Peer Concept
A fundamental principle of MeshDrop is:
**A TCP connection is not a Peer.**

- A TCP socket is an ephemeral, bidirectional byte pipe.
- A Peer is a recognized entity on the mesh that possesses a unique `NodeIdentity` (UUID).
- A raw TCP connection only becomes a `Peer` after successfully completing the two-way MeshDrop binary handshake (`HELLO` → `HELLO_RESPONSE` → `READY`).
- Sockets connecting without valid authentication or protocol compliance are rejected without ever entering `PeerManager`.

---

## 2. Bidirectional Connection Architecture

```text
Local Node                                       Remote Peer
    │                                                 │
    ├─► ServerSocket.accept() [INBOUND] ─────────────►│
    │   - Wrapped in TcpConnection                    │
    │   - Receiver thread started                     │
    │   - Handshake initiated                         │
    │                                                 │
    │◄─ Node.connectTo() [OUTBOUND] ──────────────────┤
    │   - Wrapped in TcpConnection                    │
    │   - Receiver thread started                     │
    │   - Handshake initiated                         │
    │                                                 │
```

---

## 3. TCP Server (`TcpServer`)
- Listens on `java.net.ServerSocket` bound to configured port (default `5000`, or ephemeral `0` in tests).
- Dispatches each accepted connection to virtual threads (`Thread.ofVirtual()`).
- Passes the wrapped `TcpConnection` to `TcpConnectionHandler`.

---

## 4. TCP Connection Transport (`TcpConnection`)
- Bidirectional buffered streams (`BufferedInputStream`, `BufferedOutputStream`) with 16 KiB buffer size.
- Configured with `setTcpNoDelay(true)` (disables Nagle's algorithm for sub-millisecond protocol packet transmission) and `setKeepAlive(true)`.
- Concurrent Virtual Thread Receiver: continuously frames incoming data with `PacketDecoder`.
- Synchronized Writes: outgoing `sendPacket(Packet)` synchronized on the output stream.
- Directional metadata: tagged as `ConnectionDirection.INBOUND` or `ConnectionDirection.OUTBOUND`.
- Close listeners: notifies parent `Node` and `PeerManager` immediately when transport closes for automated lifecycle cleanup.

---

## 5. Peer Manager Integration (`PeerManager`)
- Stores and indexes all mesh nodes by their permanent UUID.
- Handles automated connection association and disconnection state transitions.
- Enforces deterministic duplicate connection resolution when simultaneous connections occur.
- Exposes `findPeersByIdentifier(String)` for robust prefix, UUID, and display-name lookups.

---

## 6. UDP Multicast Peer Discovery Subsystem (`DiscoveryService`)
- **Transport**: UDP Multicast on `239.255.77.80:5001`.
- **Purpose**: Zero-configuration automated peer discovery on local area networks.
- **Independence**: UDP discovery operates entirely separately from TCP streams. Discovery notifies `Node` which registers peers in `PeerManager` as `DISCOVERED`.
- **Integrity**: Packet layout (26-byte header + UTF-8 display name) validated defensively. Peer IP derived directly from UDP datagram metadata to prevent IP spoofing.

---

## 7. Application Layer Integration: CLI to TCP Flow

```text
User Input: "send Desktop-Beta hello"
    │
    ▼
CommandLineInterface (com.meshdrop.cli)
    │  - Parses command & arguments
    │  - Resolves peer via PeerManager.findPeersByIdentifier("Desktop-Beta")
    │
    ▼
Node.sendMessage(peerId, "hello") (com.meshdrop.core)
    │
    ▼
MessageService.sendMessage(peer, "hello") (com.meshdrop.message)
    │  - Verifies peer is CONNECTED
    │  - Obtains active ready TcpConnection
    │
    ▼
Packet.createMessage("hello") (com.meshdrop.protocol)
    │  - Type: 0x05 (MESSAGE)
    │  - Request ID: UUID
    │  - Length: UTF-8 payload length
    │
    ▼
PacketEncoder.encode(packet, out)
    │  - 28-byte binary frame header (MAGIC + VER + TYPE + FLAGS + LEN + REQ_ID)
    │  - Binary UTF-8 payload bytes
    │
    ▼
TcpConnection.sendPacket(packet) (com.meshdrop.network)
    │  - Synchronized write on BufferedOutputStream
    │  - Flushed directly to OS TCP socket
    │
    ▼
Operating System TCP / IP Network Interface
```

---

## 8. Complete End-to-End Networking Pipeline

```text
UDP Multicast Beacons (DiscoveryService)
           │
           ▼
    DiscoveryListener
           │
           ▼
    PeerManager (PeerState.DISCOVERED)
           │
           ▼
ConnectionManager.tryConnect() (PeerState.CONNECTING)
           │
           ▼
    TCP Socket.connect() (Virtual Thread)
           │
           ▼
TcpConnection (ConnectionDirection.OUTBOUND)
           │
           ▼
    HandshakeService (HELLO / HELLO_RESPONSE)
           │
           ▼
ConnectionState.READY (Identity Verified)
           │
           ▼
PeerManager.registerConnected() (PeerState.CONNECTED)
           │
           ▼
Application Services (MessageService / PingService / TransferService)
           │
           ▼
CLI Event Listeners (MessageListener / Latency Futures)
```
