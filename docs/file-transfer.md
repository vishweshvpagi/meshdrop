# MeshDrop File Transfer Specification

## 1. Overview
MeshDrop Phase 11 implements a robust, memory-efficient peer-to-peer file transfer subsystem directly on top of the custom framed binary protocol and Java 26 virtual threads.

Key Design Principles:
- **Zero Third-Party Dependencies**: Exclusively utilizes the Java 26 Standard Library (`java.nio.file`, `java.security.MessageDigest`, `java.util.concurrent`).
- **Memory Safety**: Files are strictly streamed in bounded chunks ($O(\text{chunk size})$ memory usage). Files are never read into memory all at once.
- **End-to-End Cryptographic Integrity**: The sender calculates SHA-256 upfront; the receiver computes SHA-256 incrementally on disk and verifies before publication.
- **Filesystem Security**: Defense against path traversal attacks, directory isolation, non-destructive collision renaming, and staging via temporary `.part` files.
- **Asynchronous Event-Driven Coordination**: Dedicated virtual threads handle chunk streaming without blocking protocol event loops or socket threads.

---

## 2. Architecture & Components

```text
               CommandLineInterface (sendfile / transfers / [y/N])
                                  │
                                  ▼
                         FileTransferService
                                  │
      ┌───────────────────────────┼───────────────────────────┐
      ▼                           ▼                           ▼
TransferManager              FileSender                  FileReceiver
(Active Transfers)      (O(chunk) Streaming)         (.part Staging & Hashing)
      │                           │                           │
      │                     FileTransferCodec                 │
      │                (Binary Encode / Decode)               │
      └───────────────────────────┼───────────────────────────┘
                                  ▼
                                Packet (Type 0x10 - 0x18)
                                  │
                                  ▼
                            TcpConnection (Virtual Threads)
```

### Components
1. **`FileMetadata`**: Immutable transfer descriptor containing UUID transfer ID, sender ID, recipient ID, sanitized filename, file size, creation timestamp, and expected SHA-256 hash.
2. **`FileChunk`**: Sliced byte payload container tracking chunk index, byte offset, data length, and raw bytes.
3. **`FileSender`**: Reads chunks from disk sequentially, publishes `FILE_CHUNK` packets, tracks progress, and sends `FILE_COMPLETE`.
4. **`FileReceiver`**: Stages incoming chunks into `.transfer-<id>.part`, incrementally computes SHA-256, verifies size and hash, and moves to final destination with collision numbering.
5. **`Transfer`**: Domain state machine tracking progress percentage, throughput speed (B/s), transfer direction (`UPLOAD`/`DOWNLOAD`), and lifecycle state.
6. **`TransferManager`**: Thread-safe registry tracking all in-flight and completed transfers.
7. **`FileTransferService`**: Top-level coordinator routing packets, managing approvals, and enforcing timeouts.

---

## 3. Transfer State Machine

```text
UPLOAD:
OFFERING ──► WAITING_FOR_ACCEPT ──► ACCEPTED ──► TRANSFERRING ──► VERIFYING ──► COMPLETED
   │                  │                 │              │              │
   └──────────────────┴─────────────────┴──────────────┴──────────────┴──► FAILED / CANCELLED / REJECTED

DOWNLOAD:
WAITING_FOR_ACCEPT ──► ACCEPTED ──► TRANSFERRING ──► VERIFYING ──► COMPLETED
   │                       │              │              │
   └───────────────────────┴──────────────┴──────────────┴──► FAILED / CANCELLED / REJECTED
```

- **`OFFERING`**: Sender created transfer manifest and transmitted `FILE_OFFER`.
- **`WAITING_FOR_ACCEPT`**: Waiting for peer acceptance decision or user approval.
- **`ACCEPTED`**: Transfer was accepted; sender streaming thread spawned.
- **`TRANSFERRING`**: Actively transmitting or receiving file chunks.
- **`VERIFYING`**: All chunks received; receiver calculating final SHA-256 digest.
- **`COMPLETED`**: Final verification passed; file atomically promoted to downloads directory.
- **`REJECTED`**: Peer or local user declined the transfer offer.
- **`FAILED`**: I/O error, socket disconnect, hash mismatch, or offset corruption.
- **`CANCELLED`**: Cancelled by user or graceful node shutdown.

---

## 4. Binary Wire Protocol

All file transfer packets use the standard MeshDrop 28-byte binary header with magic bytes `0x4D 0x44 0x52 0x50` ("MDRP").

| Packet Type | Opcode | Payload Layout |
|---|---|---|
| `FILE_OFFER` | `0x10` | 16B TransferId + 16B SenderId + 16B RecipientId + 8B FileSize + 8B CreatedAt + 64B SHA256 + 2B NameLen + $N$ bytes FileName |
| `FILE_CHUNK` | `0x11` | 16B TransferId + 4B ChunkIndex + 8B Offset + 4B Length + $N$ bytes ChunkData |
| `FILE_COMPLETE` | `0x12` | 16B TransferId + 4B TotalChunks + 8B TotalBytes + 64B SHA256 |
| `FILE_ACCEPT` | `0x15` | 16B TransferId |
| `FILE_REJECT` | `0x16` | 16B TransferId + 2B ReasonLen + $M$ bytes Reason |
| `FILE_ACK` | `0x17` | 16B TransferId + 1B Success (1/0) + 8B Timestamp |
| `FILE_ERROR` | `0x18` | 16B TransferId + 2B MsgLen + $E$ bytes ErrorMessage |

---

## 5. Security & Safety Mechanisms

### Path Traversal Defense
The receiver never allows the sender to specify arbitrary directories or relative paths:
- Basenames only: `Path.of(rawName).getFileName().toString()`.
- Explicit character filtering: drops `/`, `\`, `..`, and `:` (Windows drive letters).
- Length limits: filenames are capped at 255 bytes.

### Temporary File Staging
- Files are staged in `temp/.transfer-<UUID>.part`.
- The temporary file is only moved to the final downloads directory once all chunks are assembled and SHA-256 matches.
- On abort or verification mismatch, the temporary `.part` file is deleted immediately.

### Collision Renaming Strategy
- If `document.pdf` already exists in `downloads/`, the receiver automatically checks `document (1).pdf`, `document (2).pdf`, etc.
- Existing files are never overwritten or truncated.

---

## 6. CLI Usage

### Send File
```text
meshdrop> sendfile Alice "C:\Data\presentation.pdf"

Preparing file...
Size: 14.2 MB
SHA-256: 4f8a...

Waiting for Alice to accept...

Transfer accepted.
Progress: 100%
SHA-256 verified.
Transfer completed.
ID: 60866179-3181-48f3-b197-130d8e8d79cf
```

### Incoming Transfer Approval
```text
Incoming file transfer:
From: Alice
File: presentation.pdf
Size: 14.2 MB
Accept? [y/N]
meshdrop> y
File transfer accepted.
```

### List Transfers
```text
meshdrop> transfers

File Transfers
--------------

1. UPLOAD (COMPLETED)
   ID:       60866179-3181-48f3-b197-130d8e8d79cf
   File:     presentation.pdf
   Progress: 100.0% (14.2 MB / 14.2 MB)
   Speed:    18.4 MB/s
```
