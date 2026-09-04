# MeshDrop Application Protocol Specification

## 1. Protocol Overview
MeshDrop operates on top of TCP for reliable stream delivery and UDP for local peer discovery.
Because TCP is an unstructured, continuous byte stream without application-level message boundaries, MeshDrop defines a deterministic, fixed-size **28-byte header** preceding every payload to provide frame delineation.

---

## 2. Binary Packet Format

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       MAGIC (4 bytes)                         |  0x4D 0x44 0x52 0x50 ("MDRP")
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|    VERSION    |     TYPE      |             FLAGS             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        PAYLOAD LENGTH                         |  (Big-Endian uint32)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                       REQUEST ID (16 bytes)                   +  (UUID: 8 bytes most-sig, 8 bytes least-sig)
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        PAYLOAD DATA ...                       |  (Variable length, N bytes)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Header Fields (28 Bytes Fixed):
| Field | Size | Data Type | Description |
|---|---|---|---|
| **MAGIC** | 4 bytes | `int` (Big-Endian) | Protocol identifier: `0x4D445250` (ASCII `"MDRP"`). Rejects alien traffic early. |
| **VERSION** | 1 byte | `byte` | Protocol version (`0x01`). Unsupported versions are rejected. |
| **TYPE** | 1 byte | `byte` | Wire code identifying the `PacketType`. |
| **FLAGS** | 2 bytes | `short` (Big-Endian) | 16-bit bitmask (e.g. `0x0001` Compressed, `0x0002` Urgent, `0x0004` Ack). |
| **PAYLOAD LENGTH** | 4 bytes | `int` (Big-Endian) | Length $N$ of following payload in bytes ($0 \le N \le 16\text{ MiB}$). |
| **REQUEST ID** | 16 bytes | `UUID` (2 x `long`) | 128-bit unique identifier for request/response correlation. |

---

## 3. Node Identity Payload Format (`HELLO` / `HELLO_RESPONSE`)

Both `HELLO` (`0x01`) and `HELLO_RESPONSE` (`0x02`) transmit the peer's `NodeIdentity` as their binary payload:

```text
+-----------------------------------------------------------+
| NODE ID (16 bytes)                                        |  (UUID: 8 bytes most-sig, 8 bytes least-sig)
+-----------------------------------------------------------+
| NAME LENGTH (2 bytes)                                     |  (Big-Endian uint16: 0 to 128 bytes)
+-----------------------------------------------------------+
| DISPLAY NAME (N bytes)                                    |  (UTF-8 encoded string)
+-----------------------------------------------------------+
```

- Total payload size: $18 + N$ bytes.
- Maximum display name size: $128$ UTF-8 bytes (`MAX_DISPLAY_NAME_BYTES`).
- `HELLO_RESPONSE` echoes the `requestId` from the incoming `HELLO` packet for correlation.

---

## 4. Packet Types (`PacketType`)

| Type ID | Name | Direction | Payload Description | Expected Response |
|---|---|---|---|---|
| `0x01` | `HELLO` | Node → Peer | Initial handshake containing `NodeIdentity` ($18 + N$ bytes). | `HELLO_RESPONSE` |
| `0x02` | `HELLO_RESPONSE` | Peer → Node | Handshake response containing responder's `NodeIdentity` ($18 + N$ bytes). | None |
| `0x03` | `PING` | Node → Peer | Heartbeat liveness probe (0 bytes payload). | `PONG` |
| `0x04` | `PONG` | Peer → Node | Heartbeat acknowledgment matching `requestId` (0 bytes). | None |
| `0x05` | `MESSAGE` | Node → Peer | Binary application message with 60B header + UTF-8 payload (Phase 10). | `MESSAGE_ACK` |
| `0x06` | `DISCOVER` | Node → Broadcast | UDP LAN discovery announcement (Phase 8). | `DISCOVER_RESPONSE` |
| `0x07` | `DISCOVER_RESPONSE` | Peer → Unicast | UDP discovery reply with connection parameters (Phase 8). | None |
| `0x08` | `MESSAGE_ACK` | Peer → Node | Delivery acknowledgement for `MESSAGE` carrying messageId + timestamp (24 bytes). | None |
| `0x10` | `FILE_OFFER` | Sender → Receiver | File transfer manifest offer (130B + $N$ bytes). | `FILE_ACCEPT` / `FILE_REJECT` |
| `0x11` | `FILE_CHUNK` | Sender → Receiver | Binary file chunk (32B header + $N$ bytes data). | None |
| `0x12` | `FILE_COMPLETE` | Sender → Receiver | Final verification manifest (92 bytes). | `FILE_ACK` |
| `0x13` | `FILE_RESUME_REQUEST` | Node → Peer | Request resumption of interrupted transfer (124 bytes). | `FILE_RESUME_RESPONSE` |
| `0x14` | `FILE_RESUME_RESPONSE`| Peer → Node | Resume negotiation response (39B header + reason). | Chunk streaming |
| `0x15` | `FILE_ACCEPT` | Receiver → Sender | Acceptance confirmation for file offer (16 bytes transfer UUID). | Chunks stream |
| `0x16` | `FILE_REJECT` | Receiver → Sender | Rejection of file offer with reason string (18B + $M$ bytes). | None |
| `0x17` | `FILE_ACK` | Receiver → Sender | Completion and SHA-256 verification acknowledgment (25 bytes). | None |
| `0x18` | `FILE_ERROR` | Node ↔ Peer | Unrecoverable transfer error message (18B + $E$ bytes). | None |
| `0xFF` | `ERROR` | Node ↔ Peer | UTF-8 encoded protocol error message. | None |

---

## 5. File Transfer Payload Layouts (Phase 11)

### 5.1 `FILE_OFFER` (`0x10`)
- **16 bytes**: Transfer UUID
- **16 bytes**: Sender UUID
- **16 bytes**: Recipient UUID
- **8 bytes**: File size (int64, big-endian)
- **8 bytes**: Created timestamp (int64, epoch ms)
- **64 bytes**: Expected SHA-256 (ASCII hex string)
- **2 bytes**: Filename byte length (uint16)
- **N bytes**: Filename (UTF-8)

### 5.2 `FILE_CHUNK` (`0x11`)
- **16 bytes**: Transfer UUID
- **4 bytes**: Chunk index (int32, big-endian)
- **8 bytes**: Byte offset (int64, big-endian)
- **4 bytes**: Data length (int32, big-endian)
- **N bytes**: Raw file chunk bytes

### 5.3 `FILE_COMPLETE` (`0x12`)
- **16 bytes**: Transfer UUID
- **4 bytes**: Total chunk count (int32, big-endian)
- **8 bytes**: Total byte count (int64, big-endian)
- **64 bytes**: Expected SHA-256 (ASCII hex string)

### 5.4 `FILE_ACCEPT` (`0x15`)
- **16 bytes**: Transfer UUID

### 5.5 `FILE_REJECT` (`0x16`)
- **16 bytes**: Transfer UUID
- **2 bytes**: Reason length (uint16)
- **M bytes**: Rejection reason (UTF-8)

### 5.6 `FILE_ACK` (`0x17`)
Used for transfer completion acknowledgments and sliding-window cumulative progress acknowledgments:
- **Standard Completion ACK (25 bytes)**:
  - **16 bytes**: Transfer UUID
  - **1 byte**: Success flag (`0x01` success, `0x00` failure)
  - **8 bytes**: Acknowledgment timestamp (int64, epoch ms)
- **Extended Sliding-Window Progress ACK (41 bytes)**:
  - **16 bytes**: Transfer UUID
  - **1 byte**: Success flag (`0x01`)
  - **8 bytes**: Acknowledgment timestamp (int64, epoch ms)
  - **8 bytes**: Highest contiguous chunk index acknowledged (int64, big-endian)
  - **8 bytes**: Receiver contiguous byte offset (int64, big-endian)

### 5.7 `FILE_ERROR` (`0x18`)
- **16 bytes**: Transfer UUID
- **2 bytes**: Error message length (uint16)
- **E bytes**: Error message (UTF-8)

### 5.8 `FILE_RESUME_REQUEST` (`0x13`) - Phase 12
- **16 bytes**: Transfer UUID
- **16 bytes**: Sender UUID
- **16 bytes**: Recipient UUID
- **8 bytes**: Total file size (int64, big-endian)
- **4 bytes**: Chunk size (int32, big-endian)
- **64 bytes**: Expected full file SHA-256 (ASCII hex string)

### 5.9 `FILE_RESUME_RESPONSE` (`0x14`) - Phase 12
- **16 bytes**: Transfer UUID
- **1 byte**: Resume status code (`0x01` ACCEPTED, `0x02` NOT_FOUND, `0x03` INVALID, `0x04` HASH_MISMATCH, `0x05` METADATA_MISMATCH, `0x06` COMPLETE, `0x07` REJECTED)
- **4 bytes**: Next expected chunk index (int32, big-endian)
- **8 bytes**: Next expected byte offset (int64, big-endian)
- **8 bytes**: Contiguous bytes received (int64, big-endian)
- **2 bytes**: Reason byte length (uint16)
- **R bytes**: UTF-8 reason string

---

## 6. Framing, Safety & Error Handling

### 6.1 Stream Decoding Rules
1. **Header Boundary**: Decoders loop on `InputStream.read()` until all 28 header bytes are read.
2. **Clean EOF vs Truncation**:
   - An EOF encountered at the start of a frame ($0$ bytes read) indicates a clean peer disconnect and returns `null`.
   - An EOF encountered during header or payload reads indicates stream truncation and throws `ProtocolException`.
3. **Payload Allocation Protection**: The length field is strictly validated ($0 \le N \le 16\text{ MiB}$) *before* allocating memory buffers.
4. **Validation Failures**: Invalid magic, version, packet types, or malformed identities throw `ProtocolException`, terminating only the offending connection.

---

## 7. Wireshark Packet Inspection Guide

Wireshark can inspect MeshDrop traffic on TCP port `5000`:
- **Display Filter**: `tcp.port == 5000`
- **Header Signature**: In the TCP payload pane, the first 4 bytes appear as hexadecimal `4d 44 52 50` (`"MDRP"`).
- **Packet Flow**:
  1. TCP Three-Way Handshake (`SYN` → `SYN-ACK` → `ACK`)
  2. Initiator sends `HELLO` (`Type = 0x01`, `Len = 18 + N`, carries Node ID + Name)
  3. Receiver responds with `HELLO_RESPONSE` (`Type = 0x02`, `Len = 18 + N`, echoes Request ID)
  4. Both sides enter `READY` state.
  5. Liveness monitoring: `PING` (`Type = 0x03`) and `PONG` (`Type = 0x04`)
  6. Application messaging: `MESSAGE` (`Type = 0x05`)
