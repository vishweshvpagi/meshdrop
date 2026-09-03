# Resumable File Transfers & Transfer Reliability (Phase 12)

## 1. Overview & Architecture

Phase 12 transforms MeshDrop's file transfer system from a "succeed or fail completely" paradigm into a robust, fault-tolerant transport pipeline that survives intentional disconnects, process termination, abrupt node crashes, and network instability.

```
       Sender Node                                          Receiver Node
    (Client or Peer)                                     (Source of Truth)
           |                                                     |
           |                  [Network Drops]                    |
           |        (Transfer Interrupted / Paused)              |
           |                                                     |
           |  FILE_RESUME_REQUEST (0x13)                         |
           |  (tid, senderId, rid, size, chunkSz, sha256)        |
           |---------------------------------------------------->|
           |                                                     | Inspects .part & .meta
           |                                                     | Validates byte size
           |                                                     | Pre-hashes existing data
           |                                                     |
           |  FILE_RESUME_RESPONSE (0x14)                        |
           |  (status: RESUME_ACCEPTED, nextChunk, offset)       |
           |<----------------------------------------------------|
           |                                                     |
           |  FileChannel.position(offset)                       |
           |  FILE_CHUNK (chunk N)                               |
           |---------------------------------------------------->| Appends to .part file
           |  FILE_CHUNK (chunk N+1)                             | Updates .meta atomically
           |---------------------------------------------------->|
           |  FILE_COMPLETE                                      |
           |---------------------------------------------------->| Verifies final SHA-256
           |  FILE_ACK (success = true)                          | Atomic move to downloads
           |<----------------------------------------------------| Checkpoint deleted
           |                                                     |
```

---

## 2. Receiver as the Source of Truth

A critical architectural principle of MeshDrop is that **the receiver is the sole authority regarding what bytes have actually reached stable non-volatile storage**.

### Why Sender Memory Cannot Be Trusted:
- If a socket connection drops abruptly, bytes buffered in OS socket buffers or in-flight across the wire may never reach the receiver.
- If the sender assumes the receiver received byte $M$ simply because the sender called `socket.write()`, resuming from $M$ would leave a corrupted hole in the receiver's file.
- Conversely, if the receiver's process was terminated mid-chunk, only bytes physically flushed to the `.part` file before the crash count.

Therefore:
1. The sender always queries the receiver via `FILE_RESUME_REQUEST`.
2. The receiver inspects its local staging area, measures the exact byte length of the `.part` file, loads the verified `.meta` checkpoint, and determines `nextExpectedChunk` and `nextExpectedOffset`.
3. The sender seeks its local file directly to `nextExpectedOffset` using `FileChannel.position()` and streams from that point forward.

---

## 3. Checkpoint Model & Crash-Safe Persistence

### 3.1 Checkpoint Data Model (`TransferCheckpoint`)
Checkpoints are represented as immutable records:
- `transferId` (`UUID`): Globally unique transfer identifier.
- `senderId` (`UUID`): Remote node uploading the file.
- `recipientId` (`UUID`): Local node receiving the file.
- `fileName` (`String`): Strictly validated sanitised filename (no path traversal, non-blank).
- `fileSize` (`long`): Total file size in bytes ($> 0$).
- `chunkSize` (`int`): Negotiated streaming chunk size in bytes ($> 0$).
- `nextExpectedChunk` (`int`): Zero-based index of the next chunk required.
- `nextExpectedOffset` (`long`): Exact byte offset in the destination file for the next chunk.
- `bytesReceived` (`long`): Total contiguous bytes verified and written to disk ($== nextExpectedOffset$).
- `expectedSha256` (`String`): 64-character lowercase hexadecimal string of the complete file's SHA-256 digest.
- `lastUpdated` (`long`): Millisecond epoch timestamp of the latest checkpoint write.

### 3.2 Crash-Safe Atomic Disk Persistence (`RecoveryManager`)
Checkpoints are persisted in the staging directory (e.g., `<baseDir>/storage/temp/`) alongside the partial files:
- **Partial File**: `.transfer-<transferId>.part`
- **Checkpoint Metadata**: `.transfer-<transferId>.meta`

To prevent metadata corruption during mid-write system crashes or power outages:
1. Checkpoint key-value text is written to `.transfer-<transferId>.meta.tmp`.
2. The stream is flushed and physically synchronized to storage.
3. The temporary file is atomically replaced over `.transfer-<transferId>.meta` using `StandardCopyOption.ATOMIC_MOVE` (with `REPLACE_EXISTING` fallback).

### 3.3 Text-Based Checkpoint Format (UTF-8 Key-Value)
```text
transferId=e5b22ee3-e0b1-4672-a84a-b2febc7230d5
senderId=ca63ec96-ee5c-448f-bd03-d159655a6569
recipientId=b0a38e0e-e07e-4a4e-804c-4eaa86c471da
fileName=report.pdf
fileSize=10485760
chunkSize=65536
nextExpectedChunk=75
nextExpectedOffset=4915200
bytesReceived=4915200
expectedSha256=2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
lastUpdated=1725350400000
```

---

## 4. Resume Protocol Specification

Two binary packet types govern the negotiation of transfer resumption:

### 4.1 `FILE_RESUME_REQUEST` (`0x13`)
Sent by the sender or receiver to negotiate resumption.
**Payload Layout (124 bytes fixed)**:
- `16 bytes`: `transferId` (UUID)
- `16 bytes`: `senderId` (UUID)
- `16 bytes`: `recipientId` (UUID)
- `8 bytes`: `fileSize` (int64, Big-Endian)
- `4 bytes`: `chunkSize` (int32, Big-Endian)
- `64 bytes`: `expectedSha256` (ASCII hex characters)

### 4.2 `FILE_RESUME_RESPONSE` (`0x14`)
Sent in response to a resume request.
**Payload Layout (39 bytes header + variable length UTF-8 reason)**:
- `16 bytes`: `transferId` (UUID)
- `1 byte`: `status` (`ResumeStatus` byte code)
- `4 bytes`: `nextExpectedChunk` (int32, Big-Endian)
- `8 bytes`: `nextExpectedOffset` (int64, Big-Endian)
- `8 bytes`: `bytesReceived` (int64, Big-Endian)
- `2 bytes`: `reasonLength` (uint16, Big-Endian)
- `N bytes`: `reason` (UTF-8 string explaining status)

### 4.3 Status Codes (`ResumeStatus`)
| Status Code | Byte | Description |
|---|---|---|
| `RESUME_ACCEPTED` | `0x01` | Checkpoint verified. Receiver is ready to accept chunks starting from `nextExpectedChunk`. |
| `RESUME_NOT_FOUND` | `0x02` | No checkpoint exists for the requested `transferId`. |
| `RESUME_INVALID` | `0x03` | Disk inconsistency (e.g. `.part` file size does not equal `bytesReceived`). |
| `RESUME_HASH_MISMATCH` | `0x04` | Expected SHA-256 digest differs from original offer. |
| `RESUME_METADATA_MISMATCH`| `0x05` | File size or recipient ID does not match checkpoint. |
| `RESUME_COMPLETE` | `0x06` | Transfer was already completed and verified earlier. |
| `RESUME_REJECTED` | `0x07` | Peer explicitly rejected the resume request. |

---

## 5. Streaming Engine Resumption & Integrity

### 5.1 Sender Seeking
When `RESUME_ACCEPTED` is received:
- `FileSender` opens the local source file with `FileChannel.open(path, StandardOpenOption.READ)`.
- It executes `channel.position(startOffset)`, seeking immediately to the resume point.
- It begins reading chunks starting at `startChunkIndex`. Previous chunks $0 \dots N-1$ are neither read nor loaded into memory, guaranteeing constant $\mathcal{O}(\text{chunk size})$ memory usage.

### 5.2 Receiver Hashing Across Resumes
When resuming an interrupted transfer:
- `FileReceiver` does not assume the unread hash state.
- It opens the existing `.part` file, reads the persisted bytes ($0 \dots \text{offset}$), and feeds them into the active `MessageDigest (SHA-256)`.
- New incoming chunks continue updating this digest in-flight.
- When `FILE_COMPLETE` is received, the entire stream digest is finalized and compared against `expectedSha256`.

---

## 6. Crash Recovery & Startup Scanning

When a MeshDrop node starts up:
1. `Node.start()` invokes `fileTransferService.scanAndRegisterRecoverableTransfers()`.
2. `RecoveryManager` scans the staging directory for `.transfer-*.meta` files.
3. For each metadata file:
   - Validates key-value structure and ensures no path traversal.
   - Checks that the companion `.transfer-*.part` file exists.
   - Confirms `Files.size(partFile) == checkpoint.bytesReceived()`.
4. Consistent checkpoints are registered into `TransferManager` with state `RESUMABLE`.
5. Transfers are **not** auto-started; they are presented to the user via the CLI as recoverable transfers eligible for resumption.

---

## 7. Interactive CLI Commands & Usage

### 7.1 View Transfers
The `transfers` command lists categorized active, recoverable, and completed transfers:

```text
meshdrop> transfers
========================================================================================
 Active Transfers (0)
----------------------------------------------------------------------------------------
 No active transfers.

 Recoverable Transfers (1)
----------------------------------------------------------------------------------------
 ID                                   Name                 Progress   Dir      Status
 ------------------------------------ -------------------- ---------- -------- ----------
 e5b22ee3-e0b1-4672-a84a-b2febc7230d5 report.pdf               46.9%  DOWNLOAD RESUMABLE

 Completed Transfers (2)
----------------------------------------------------------------------------------------
 ID                                   Name                 Progress   Dir      Status
 ------------------------------------ -------------------- ---------- -------- ----------
 a810f274-0f2c-4734-912b-6c459c09268f photo.jpg               100.0%  UPLOAD   COMPLETED
 932f14aa-1456-4293-8ef4-9547d25e8654 database.sql            100.0%  DOWNLOAD COMPLETED
========================================================================================
```

### 7.2 Resume a Transfer
Resume an interrupted transfer using its full UUID or unique prefix:
```text
meshdrop> resume e5b22ee3
[INFO] Attempting to resume transfer e5b22ee3-e0b1-4672-a84a-b2febc7230d5 with peer Alice...
[INFO] Transfer resumed from chunk 75 (4915200 bytes)
[INFO] Transfer e5b22ee3-e0b1-4672-a84a-b2febc7230d5 completed and verified!
```

### 7.3 Cancel a Transfer
Cancel an active or recoverable transfer and permanently remove partial staging files:
```text
meshdrop> cancel e5b22ee3
[INFO] Cancelled transfer e5b22ee3-e0b1-4672-a84a-b2febc7230d5. Staged artifacts cleaned up.
```
