# MeshDrop UDP Multicast Peer Discovery Specification

## 1. Overview & Core Philosophy
A foundational architectural principle of MeshDrop is:
**UDP is for Discovery. TCP is for Reliable Communication.**

- **UDP Multicast**: Enables zero-configuration automatic discovery of nearby MeshDrop nodes on a local area network (LAN) without needing a central server or static IP configuration.
- **TCP Transport**: Once a peer is discovered, all interactive messaging, handshake authentication, and file data streaming occur exclusively over reliable, bidirectional TCP streams.
- **Security Boundary**: Receiving a UDP discovery beacon only registers a peer in `PeerManager` as `DISCOVERED`. It does **not** authenticate identity or establish a connection directly.
- **Connection Pipeline**: `DiscoveryService` discovers peers on the LAN; it does **NOT** establish TCP connections. Discovery events notify `PeerManager`, which in turn triggers `ConnectionManager` to initiate outbound TCP connections.

```text
Node A (Announcing)                               Local Network (UDP)                            Node B (Listening)
      │                                                   │                                               │
      ├────── DiscoveryMessage (BEACON) ─────────────────►│                                               │
      │       - Node ID (UUID)                            │                                               │
      │       - Display Name                              ├────── DatagramPacket Received ───────────────►│
      │       - TCP Port (e.g. 5000)                      │       - Source IP: Datagram source            │
      │                                                   │       - Advertised TCP Port                   │
      │                                                   │       - Node ID & Name                        │
      │                                                   │                                               │
      │                                                   │       DiscoveryService                        │
      │                                                   │               │                               │
      │                                                   │               ▼                               │
      │                                                   │          PeerManager                          │
      │                                                   │               │                               │
      │                                                   │               ▼                               │
      │                                                   │       PeerState.DISCOVERED                    │
      │                                                   │               │                               │
      │                                                   │               ▼                               │
      │                                                   │       ConnectionManager                       │
      │                                                   │               │                               │
      │                                                   │               ▼ (TCP Connect & Handshake)     │
      │                                                   │       PeerState.CONNECTED                     │
```

---

## 2. Multicast Network Configuration
- **Multicast Group**: `239.255.77.80`
  - Uses an administratively scoped IPv4 multicast address within the `239.0.0.0/8` private multicast range (RFC 2365).
  - Configurable via `NodeConfig.discoveryMulticastGroup`.
- **UDP Discovery Port**: `5001`
  - Dedicated UDP port separate from the default TCP port `5000`.
  - Configurable via `NodeConfig.udpDiscoveryPort`.
- **Socket Reuse**: Sockets bind with `SO_REUSEADDR` enabled, allowing multiple MeshDrop node processes on the same host to share the multicast group and port during local testing.
- **Multi-Interface Joining**: The discovery service iterates all active network interfaces (`NetworkInterface.getNetworkInterfaces()`) and joins the multicast group on all interfaces supporting multicast (`netIf.isUp() && netIf.supportsMulticast()`).
- **Beacon Announcement Interval**: Default `5000 ms` (5 seconds). Emitted asynchronously using a single-threaded daemon `ScheduledExecutorService`.

---

## 3. Binary Discovery Message Wire Format
Discovery beacons do not use Java serialization, JSON, or ad-hoc strings. They are encoded into a compact, big-endian binary frame.

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       MAGIC ("MDRP")                          |  4 bytes
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|    VERSION    |  MSG TYPE (06)|         NODE ID ...           |  2 bytes
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         ... NODE ID ...                       |  16 bytes total
|                       (128-bit UUID)                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           TCP PORT            |          NAME LENGTH          |  4 bytes
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     DISPLAY NAME (UTF-8) ...                  |  N bytes
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Field Definitions:
1. **`MAGIC`** (`4 bytes`, uint32 Big-Endian): Fixed magic bytes `0x4D445250` (`"MDRP"`).
2. **`VERSION`** (`1 byte`, uint8): Current protocol version `0x01`.
3. **`MESSAGE TYPE`** (`1 byte`, uint8): Discovery message code `0x06` (`TYPE_BEACON`).
4. **`NODE ID`** (`16 bytes`, UUID): 128-bit node identifier (8 bytes most-significant, 8 bytes least-significant).
5. **`TCP PORT`** (`2 bytes`, uint16 Big-Endian): Advertised TCP listening port (`1` to `65535`).
6. **`NAME LENGTH`** (`2 bytes`, uint16 Big-Endian): Byte length of the UTF-8 display name (`0` to `128` bytes).
7. **`DISPLAY NAME`** (`N bytes`, UTF-8): Human-readable node name.

- **Header Size**: Fixed 26 bytes (`4 + 1 + 1 + 16 + 2 + 2`).
- **Total Packet Size**: `26 + N` bytes.
- **Maximum Packet Limit**: `512 bytes` (`DiscoveryConstants.MAX_DISCOVERY_PACKET_SIZE`).

---

## 4. Validation & Defensive Parsing Rules
All incoming UDP packets are treated as untrusted network inputs and rigorously validated:
1. **Magic Validation**: Packets not starting with `0x4D445250` are rejected immediately.
2. **Version Compatibility**: Unsupported protocol versions throw a `ProtocolException` and are ignored.
3. **Message Type**: Only `0x06` (`TYPE_BEACON`) is processed in Phase 7.
4. **TCP Port Bounds**: Port values `< 1` or `> 65535` are rejected.
5. **Name Length Limits**: Name length field cannot exceed `ProtocolConstants.MAX_DISPLAY_NAME_BYTES` (`128 bytes`).
6. **Length & Trailing Bytes**: The datagram payload length must equal `26 + nameLength`. Truncated packets or packets containing extraneous trailing bytes are rejected.
7. **Packet Size Ceiling**: Packets exceeding `512 bytes` are dropped before allocation.
8. **Fault Isolation**: Protocol exceptions in the UDP receiver loop are caught and logged at diagnostic level; they never crash the `DiscoveryService` or the `Node`.

---

## 5. Peer Address Derivation & Anti-Spoofing
To prevent IP spoofing attacks where a sender embeds a fake IP in the payload:
- **IP Address Source**: Derived directly from the UDP packet socket metadata via `DatagramPacket.getAddress().getHostAddress()`.
- **TCP Port Source**: Taken from the verified beacon payload.
- **PeerAddress Construct**: `new PeerAddress(packet.getAddress().getHostAddress(), msg.tcpPort())`.

---

## 6. PeerManager Integration & Lifecycle
When a valid beacon is received:
1. **Self-Discovery Filter**: If `remoteNodeId.equals(localNodeId)`, the packet is discarded immediately.
2. **First-Time Discovery**: A new `Peer` record is created with `PeerState.DISCOVERED`, recording `NodeIdentity`, `PeerAddress`, and `lastSeen`.
3. **Repeated Discovery**: When subsequent beacons arrive from an already known peer:
   - `lastSeen` timestamp is updated to `Instant.now()`.
   - `PeerAddress` is updated if the remote IP changed (e.g. DHCP renewal or Wi-Fi roaming).
   - Display name is updated if modified.
   - **No Duplicate Records**: Lookups and storage are strictly keyed by the 128-bit `UUID`.
4. **State Protection**: If a peer is already in `PeerState.CONNECTED`, incoming discovery beacons **must not** downgrade its state to `DISCOVERED`.

---

## 7. Discovery Lifecycle in Node
```text
Node.start()
    ├── 1. Validate Configuration
    ├── 2. Start TcpServer (binds TCP port 5000)
    ├── 3. Start ConnectionManager (listens on PeerManager for DISCOVERED peers)
    ├── 4. Start DiscoveryService (binds UDP 5001, joins multicast group, starts receiver & beacon scheduler)
    └── 5. Node enters NodeState.RUNNING

Node.stop()
    ├── 1. DiscoveryService.stop() (cancels scheduler, leaves multicast group, closes DatagramSocket)
    ├── 2. ConnectionManager.stop() (cancels in-flight attempts, rejects future connect requests)
    ├── 3. TcpServer.close()
    ├── 4. Close active TcpConnection instances
    ├── 5. Update PeerManager
    └── 6. Node enters NodeState.STOPPED
```
- **Optional Failure**: If UDP multicast binding fails (e.g. network interface restrictions), the error is logged as a warning and the node continues running in TCP-only mode without crashing.
