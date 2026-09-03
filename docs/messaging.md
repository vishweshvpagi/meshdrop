# MeshDrop Messaging Subsystem Specification (Phase 10)

## 1. Overview
The MeshDrop Messaging Subsystem provides reliable, direct application-level text messaging between LAN peers. Built above persistent TCP connections, it introduces structured domain messaging with cryptographic UUID identifiers, microsecond timestamps, sender/recipient verification, bounded duplicate suppression, and reliable request/response delivery acknowledgements (`MESSAGE_ACK`).

---

## 2. Architecture

```text
                     CommandLineInterface (CLI)
                               │
                               ▼
                         MessageService
                               │
       ┌───────────────────────┼───────────────────────┐
       ▼                       ▼                       ▼
MessageCodec         PendingMessageRegistry       DuplicateCache
(60B + UTF-8)           (In-flight ACKs)        (Bounded LRU 1000)
       │                       │
       ▼                       │
     Packet ◄──────────────────┘
       │
       ▼
  TcpConnection (Virtual Threads)
       │
       ▼
   Peer Node
```

### Layer Separation
1. **CLI Layer**: Manages user interaction and console formatting. Has no access to sockets or raw byte streams.
2. **MessageService Layer**: High-level orchestrator. Validates message content and size, enforces identity rules, tracks delivery futures, and dispatches incoming events.
3. **PendingMessageRegistry**: Thread-safe correlation map (`messageId -> CompletableFuture<MessageDeliveryResult>`) with asynchronous timeout scheduling.
4. **Protocol / Codec Layer**: Frames messages and ACKs into standard 28-byte headers + deterministic binary payloads.
5. **Transport Layer (`TcpConnection`)**: Reliable bidirectional stream over TCP using Java 26 virtual threads.

---

## 3. Message Domain Model

The `Message` domain object is an immutable record:
- **`messageId`** (`UUID`): Globally unique identifier generated via `UUID.randomUUID()`.
- **`senderId`** (`UUID`): Node UUID of the originator.
- **`recipientId`** (`UUID`): Target peer's Node UUID.
- **`timestamp`** (`long`): Epoch millisecond timestamp captured at instantiation. Preserved identically across encoding, transmission, and decoding.
- **`content`** (`String`): Non-empty text string encoded as UTF-8.

### Validation Rules
Before transmission and upon decoding, every message must satisfy:
1. `messageId != null`
2. `senderId != null`
3. `recipientId != null`
4. `timestamp > 0`
5. `content != null` and `!content.isEmpty()`
6. `content.getBytes(StandardCharsets.UTF_8).length <= ProtocolConstants.MAX_MESSAGE_BYTES` (64 KiB = 65,536 bytes)

---

## 4. Binary Wire Format

### 4.1 MESSAGE Packet Payload (Type code `0x05`)
The packet payload contains a 60-byte fixed binary header followed by variable-length UTF-8 content bytes:

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      messageId (16 bytes)                     |
|                   (8 bytes most, 8 bytes least)               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       senderId (16 bytes)                     |
|                   (8 bytes most, 8 bytes least)               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      recipientId (16 bytes)                   |
|                   (8 bytes most, 8 bytes least)               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     timestamp (8 bytes, long)                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                  contentLength (4 bytes, int)                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     UTF-8 Content (N bytes)                   |
|                               ...                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

Total payload length: `60 + N` bytes.
Byte order: Big-endian (Network Byte Order).

### 4.2 MESSAGE_ACK Packet Payload (Type code `0x08`)
Delivery acknowledgement carrying the corresponding message identifier and acknowledgement timestamp:

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      messageId (16 bytes)                     |
|                   (8 bytes most, 8 bytes least)               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                   ackTimestamp (8 bytes, long)                |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

Total payload length: `24` bytes.

---

## 5. Security & Validation Controls

### Sender Identity Verification
When an incoming `MESSAGE` packet is received over a `TcpConnection`, the message's `senderId` is strictly verified against `connection.getRemoteIdentity().nodeId()`.
If a mismatched or forged `senderId` is detected, the packet is logged as a security warning and discarded without delivering it to registered listeners.

### Recipient Identity Verification
The message's `recipientId` is verified against the local node's `NodeIdentity.nodeId()`. If the message is addressed to any other node, it is discarded. Multi-hop routing is not supported in Phase 10.

### Duplicate Suppression
`MessageService` maintains a thread-safe, bounded LRU cache (`maxSize = 1000`) of recently processed `messageId`s.
- When a duplicate packet arrives (e.g. retransmission due to network delay), an ACK is immediately returned to prevent the sender from timing out.
- The message is suppressed and **not** delivered to listeners a second time.

---

## 6. Asynchronous Acknowledgement & Correlation

- When sending a message, `PendingMessageRegistry` creates a `CompletableFuture<MessageDeliveryResult>`.
- An asynchronous virtual thread timer handles timeout enforcement (default `5000` ms).
- When a matching `MESSAGE_ACK` arrives, the registry matches the `messageId`, completes the future with `MessageDeliveryResult.success(messageId)`, and removes the tracking entry.
- If timeout expires, the future completes with `MessageDeliveryResult.Status.TIMEOUT`.
- During node shutdown, `MessageService.stop()` cancels all pending futures with `MessageDeliveryResult.Status.NODE_SHUTTING_DOWN`, preventing thread or memory leaks.

---

## 7. Delivery Result Statuses

| Status | Description |
| :--- | :--- |
| `SUCCESS` | Message delivered and acknowledged by remote peer. |
| `PEER_NOT_FOUND` | Specified peer identifier could not be resolved. |
| `NOT_CONNECTED` | Peer exists but is in DISCOVERED or DISCONNECTED state. |
| `NOT_READY` | Connection exists but TCP handshake is not yet READY. |
| `MESSAGE_TOO_LARGE` | Content exceeds 64 KiB UTF-8 byte limit. |
| `INVALID_MESSAGE` | Empty content or attempt to send message to self. |
| `SEND_FAILED` | Socket write exception during packet transmission. |
| `TIMEOUT` | Delivery ACK was not received within the timeout window. |
| `NODE_SHUTTING_DOWN` | Operation aborted because local node is stopping. |

---

## 8. CLI Usage

### Sending Messages
```text
meshdrop> send Alice-PC "Hello from Bob!"
Message sent.
ID: a472b537-8e6d-4786-905e-8561726a42a9
```

### Receiving Messages
Incoming messages display with timestamp and sender display name:
```text
[14:32:05] Alice-PC:
Hello from Bob!
meshdrop> 
```

---

## 9. Current Limitations & Non-Goals for Phase 10
1. **Direct P2P Only**: Messages travel strictly between directly connected TCP peers; multi-hop mesh routing is not supported.
2. **Online Delivery Only**: No offline mailboxing or store-and-forward queuing.
3. **In-Memory Only**: No persistent SQLite/file-based chat history is maintained.
4. **Unencrypted Transport**: TLS/Noise protocol encryption is reserved for a future security phase.
5. **No File Transfer**: File chunking and binary transfer are reserved for Phase 11+.
