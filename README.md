# MeshDrop

> **A resilient, decentralized peer-to-peer LAN messaging and high-speed file transfer system built from absolute scratch using Java 26 and the standard library only.**

[![Java 26](https://img.shields.io/badge/Java-26-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Zero Dependencies](https://img.shields.io/badge/Dependencies-0-success)](https://github.com/)
[![Tests](https://img.shields.io/badge/Test%20Suites-75%2F75%20Passed-brightgreen)](scripts/test.ps1)
[![Platform](https://img.shields.io/badge/Platform-Windows%20PowerShell-blue)](https://microsoft.com/)

---

## 1. Overview

**MeshDrop** is an autonomous peer-to-peer (P2P) local area network application enabling instant peer discovery, persistent bidirectional communication, reliable text messaging with delivery acknowledgements, and crash-resilient, chunked, resumable file transfers.

### Core Engineering Principles
- **Absolute Zero External Dependencies**: Built strictly using `java.base` (`java.net`, `java.nio`, `java.io`, `java.util.concurrent`, `java.security`). No Netty, no Spring, no Maven, no Gradle, no BouncyCastle, no Jackson.
- **Modern Concurrency**: Driven by **Java Virtual Threads** (`Thread.ofVirtual()`) and non-blocking stream synchronization for massive concurrency with minimal memory overhead.
- **Zero-Trust Cryptographic Identity**: Every node generates persistent **Ed25519** public/private keypairs, verified via deterministic 32-character uppercase hexadecimal fingerprints.
- **Custom Binary Wire Protocol**: Deterministic 28-byte framing with magic header validation, stream boundaries, CRC integrity, and explicit packet type routing.
- **Receiver-Authoritative Resumable Transfers**: Crash-safe `.meta` checkpoints and `.part` sparse disk staging enable seamless mid-stream transfer recovery across process restarts or abrupt network drops.

---

## 2. Architecture

MeshDrop is structured into decoupled, single-responsibility subsystems coordinated by the central `Node` orchestrator:

```text
                                 ┌──────────────────────────────┐
                                 │   CommandLineInterface       │
                                 │   (Interactive Shell / CLI)  │
                                 └──────────────┬───────────────┘
                                                │
                                 ┌──────────────▼───────────────┐
                                 │             Node             │
                                 │   (Central Orchestrator)     │
                                 └──────┬───────┬────────┬──────┘
                                        │       │        │
               ┌────────────────────────┼───────┼────────┴───────────────────────┐
               │                        │       │                                │
┌──────────────▼─────────────┐ ┌────────▼───────▼──────┐ ┌───────────────────────▼──────────────┐
│       Network Layer        │ │       Peer Layer      │ │             Services Layer           │
├────────────────────────────┤ ├───────────────────────┤ ├──────────────────────────────────────┤
│ • TcpServer (Port 5000)    │ │ • PeerManager         │ │ • MessageService (Reliable Msg / ACK)│
│ • ConnectionManager        │ │ • Peer (Model/State)  │ │ • PingService (RTT Latency)          │
│ • TcpConnection (Streams)  │ │ • PeerAddress         │ │ • FileTransferService (Streaming)    │
│ • TcpConnectionHandler     │ │ • Deduplication (UUID)│ │ • DiscoveryService (UDP Multicast)   │
└──────────────┬─────────────┘ └───────────────────────┘ └───────────────────────┬──────────────┘
               │                                                                 │
               │               ┌────────────────────────────────┐                │
               └──────────────►│        Security & Storage      │◄───────────────┘
                               ├────────────────────────────────┤
                               │ • Ed25519 Cryptographic Keys   │
                               │ • IdentityFingerprint          │
                               │ • TrustStore (Persistence)     │
                               │ • StorageManager (Sandboxing)  │
                               └────────────────────────────────┘
```

---

## 3. Custom Binary Protocol

All TCP communication is framed using a 28-byte big-endian binary packet header:

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     MAGIC (0x4D445250 "MDRP")                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  VERSION (1)  |  FLAGS (0x00) |       PACKET_TYPE (uint16)    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         PAYLOAD_LENGTH                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                       SEQUENCE_NUMBER                         +
|                           (uint64)                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           CHECKSUM                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        PAYLOAD_BYTES ...                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Packet Type Registry

| Type Code | Name | Description |
| :---: | :--- | :--- |
| `0x0001` | `HELLO` | Outbound peer identity handshake containing UUID, name, Ed25519 key |
| `0x0002` | `HELLO_RESPONSE` | Inbound handshake acknowledgement confirming session readiness |
| `0x0003` | `PING` | Bidirectional liveness probe containing monotonic timestamp |
| `0x0004` | `PONG` | Response echo used to compute round-trip network latency |
| `0x0010` | `MESSAGE` | Application text message with UUID, sender/recipient verification |
| `0x0011` | `MESSAGE_ACK` | Reliable delivery receipt confirming application reception |
| `0x0020` | `FILE_OFFER` | File transfer proposal with filename, size, and SHA-256 digest |
| `0x0021` | `FILE_ACCEPT` | Receiver consent to accept transfer and prepare local staging |
| `0x0022` | `FILE_REJECT` | Receiver refusal of file transfer |
| `0x0023` | `FILE_CHUNK` | Binary data slice with chunk index and byte offset |
| `0x0024` | `FILE_COMPLETE` | Finalization packet triggering SHA-256 integrity validation |
| `0x0025` | `FILE_RESUME_REQUEST` | Receiver request to resume transfer at specific chunk/offset |
| `0x0026` | `FILE_RESUME_RESPONSE` | Sender confirmation of resume offset and upload continuation |
| `0x0027` | `FILE_CANCEL` | Abort signal for in-progress transfers |

---

## 4. Security & Trust Architecture

1. **Persistent Keypairs**: Automatically initialized or loaded from `<dataDir>/identity/node_identity.properties` using standard Java Ed25519 algorithms.
2. **Deterministic Fingerprints**: Hexadecimal 32-character tokens formatted as `AB12-CD34-EF56-7890-1234-5678-90AB-CDEF` derived from `SHA-256(PublicKey)`.
3. **Crash-Safe Trust Store**: Persists user trust choices to `<dataDir>/trust/trust_store.txt` using `.tmp` -> `ATOMIC_MOVE` staging.
4. **MITM Impersonation Defense**: If an incoming peer presents a known UUID but a modified public key fingerprint, the connection is instantly severed and permanently `BLOCKED`.
5. **Directory Traversal Defense**: All downloads pass through strict path canonicalization. Drive letters (`C:`) and relative steps (`..`) are stripped, ensuring received files cannot escape `downloads/`.

---

## 5. Requirements

- **Java 26 JDK** (or standard OpenJDK with Java 26 bytecode support).
- **Windows PowerShell** (or PowerShell 7+ on Windows).
- **Zero third-party installations** required (no Gradle, Maven, or external libraries).

---

## 6. Quick Start

### Build the Project
Compiles all Java source files into `out/`:
```powershell
.\scripts\build.ps1
```

### Run Full Regression Test Suite
Compiles and executes all 75 automated test suites:
```powershell
.\scripts\test.ps1
```

### Run an Interactive Node
```powershell
.\scripts\run.ps1
```

---

## 7. Demonstrations

### 1. Automated Live Two-Node Demo
Demonstrates two autonomous nodes running on ephemeral ports, discovering each other, establishing a TCP session, handshaking, measuring latency, exchanging messages with ACK, transferring a binary file, verifying SHA-256 integrity, and shutting down cleanly:
```powershell
.\scripts\demo.ps1
```

### 2. Automated Resumable File Transfer Demo
Demonstrates resilience against abrupt network severance mid-stream, verification of persistent disk checkpoints (`.part` and `.meta`), reconnection, receiver-authoritative resume request, and final SHA-256 verification:
```powershell
.\scripts\demo_resume.ps1
```

### 3. Manual Multi-Terminal Demonstration
Launch two separate terminals on the same machine with isolated data directories:

**Terminal 1 (Alice):**
```powershell
.\scripts\run_demo.ps1 -Name Alice -TcpPort 5001 -DiscoveryPort 6001
```

**Terminal 2 (Bob):**
```powershell
.\scripts\run_demo.ps1 -Name Bob -TcpPort 5002 -DiscoveryPort 6002
```

In Alice's terminal:
```text
meshdrop> peers
meshdrop> ping Bob
meshdrop> send Bob "Hello Bob!"
meshdrop> sendfile Bob sample_demo.bin
meshdrop> trust Bob
meshdrop> status
```

---

## 8. Interactive CLI Commands

| Command | Arguments | Description |
| :--- | :--- | :--- |
| `status` | None | Displays node state, uptime, connection counts, and transfer stats |
| `info` | None | Displays local node UUID, display name, public key fingerprint, and paths |
| `peers` | None | Tabular list of discovered and connected peers with trust states |
| `connections`| None | Detailed view of active TCP connections, directions, and idle durations |
| `discover` | None | Triggers an immediate UDP multicast peer discovery broadcast |
| `ping` | `<peer>` | Sends a latency probe to a remote peer and displays round-trip time |
| `send` | `<peer> <msg>` | Sends an acknowledged text message to a peer |
| `sendfile` | `<peer> <path>`| Sends a file to a remote peer with real-time transfer progress |
| `transfers` | None | Displays active, resumable, and completed file transfers |
| `resume` | `<transferId>` | Resumes an interrupted file transfer from last verified chunk |
| `cancel` | `<transferId>` | Cancels an active or pending file transfer |
| `trust` | `<peer>` | Explicitly trusts a peer's identity and pins their fingerprint |
| `untrust` | `<peer>` | Sets a peer back to default UNTRUSTED status |
| `block` | `<peer>` | Blacklists a peer and severs all active connections |
| `clear` | None | Clears the terminal screen |
| `exit` / `quit`| None | Initiates orderly graceful shutdown and terminates CLI |

---

## 9. Test Suite Summary

MeshDrop includes a standalone, zero-dependency test runner executing **75 distinct test suites** covering all layers of the architecture:

```text
=========================================
 MeshDrop Test Suite Execution
=========================================
[RUN]  PacketEncoderTest ......... PASSED
[RUN]  PacketDecoderTest ......... PASSED
[RUN]  HandshakeTest ............. PASSED
[RUN]  TcpServerTest ............. PASSED
[RUN]  TcpConnectionTest ......... PASSED
[RUN]  PeerTest .................. PASSED
[RUN]  PeerManagerTest ........... PASSED
[RUN]  DiscoveryMessageTest ...... PASSED
[RUN]  DiscoveryServiceTest ...... PASSED
[RUN]  ConnectionManagerTest ..... PASSED
[RUN]  MessageCodecTest .......... PASSED
[RUN]  MessageTest ............... PASSED
[RUN]  MessageAckTest ............ PASSED
[RUN]  MessageDeduplicationTest .. PASSED
[RUN]  MessageServiceTest ........ PASSED
[RUN]  ConcurrentMessagingTest ... PASSED
[RUN]  ShutdownMessagingTest ..... PASSED
[RUN]  MessageListenerTest ....... PASSED
[RUN]  MessageAckTimeoutTest ..... PASSED
[RUN]  UnicodeMessagingTest ...... PASSED
[RUN]  SenderIdentityValidation .. PASSED
[RUN]  RecipientValidation ....... PASSED
[RUN]  CommandParserTest ......... PASSED
[RUN]  CommandLineInterfaceTest .. PASSED
[RUN]  TwoNodeIntegrationTest .... PASSED
[RUN]  TwoNodeMessagingTest ...... PASSED
[RUN]  NodeCliIntegrationTest .... PASSED
[RUN]  CliConcurrencyTest ........ PASSED
[RUN]  HashUtilsTest ............. PASSED
[RUN]  ChunkManagerTest .......... PASSED
[RUN]  TransferTest .............. PASSED
[RUN]  FileMetadataTest .......... PASSED
[RUN]  FileChunkTest ............. PASSED
[RUN]  FileMetadataCodecTest ..... PASSED
[RUN]  FileChunkCodecTest ........ PASSED
[RUN]  FileHashTest .............. PASSED
[RUN]  FileSenderTest ............ PASSED
[RUN]  FileReceiverTest .......... PASSED
[RUN]  TransferStateTest ......... PASSED
[RUN]  TransferManagerTest ....... PASSED
[RUN]  FileTransferServiceTest ... PASSED
[RUN]  TwoNodeFileTransferTest ... PASSED
[RUN]  BinaryFileTransferTest .... PASSED
[RUN]  LargeFileTransferTest ..... PASSED
[RUN]  UnicodeFilenameTest ....... PASSED
[RUN]  CollisionTest ............. PASSED
[RUN]  PathTraversalTest ......... PASSED
[RUN]  HashMismatchTest .......... PASSED
[RUN]  DisconnectDuringTransfer .. PASSED
[RUN]  ConcurrentTransfersTest ... PASSED
[RUN]  ShutdownDuringTransfer .... PASSED
[RUN]  TransferCheckpointTest .... PASSED
[RUN]  CheckpointAtomicWriteTest . PASSED
[RUN]  ResumeRequestCodecTest .... PASSED
[RUN]  ResumeResponseCodecTest ... PASSED
[RUN]  InterruptedTransferTest ... PASSED
[RUN]  ResumeTransferTest ........ PASSED
[RUN]  NoDuplicateDataTest ....... PASSED
[RUN]  TwoNodeResumeTest ......... PASSED
[RUN]  ResumeAfterRestartTest .... PASSED
[RUN]  WrongOffsetTest ........... PASSED
[RUN]  CheckpointMismatchTest .... PASSED
[RUN]  MetadataMismatchResumeTest  PASSED
[RUN]  CompletedTransferResume ... PASSED
[RUN]  RestartRecoveryTest ....... PASSED
[RUN]  CancelTransferTest ........ PASSED
[RUN]  ConcurrentResumeTest ...... PASSED
[RUN]  NetworkFailureTest ........ PASSED
[RUN]  LargeFileResumeTest ....... PASSED
[RUN]  CorruptedPartialFileTest .. PASSED
[RUN]  PathTraversalRecoveryTest . PASSED
[RUN]  IdentityFingerprintTest ... PASSED
[RUN]  CryptoUtilsTest ........... PASSED
[RUN]  TrustStoreTest ............ PASSED
[RUN]  StorageManagerTest ........ PASSED
=========================================
Results: 75 passed, 0 failed (100%)
=========================================
```

---

## 10. Repository File Structure

```text
MeshDrop/
├── README.md                           # Comprehensive documentation & quick start
├── docs/                               # Architectural and protocol specifications
│   ├── architecture.md                 # System components and interaction diagrams
│   ├── protocol.md                     # Binary wire packet framing specification
│   ├── state_machine.md                # Connection & transfer lifecycle state machines
│   ├── security.md                     # Cryptographic identity & trust model
│   ├── demo.md                         # Demonstration guide & instructions
│   ├── cli.md                          # Interactive shell command manual
│   ├── file-transfer.md                # Chunked streaming & hashing design
│   ├── resumable-transfers.md          # Checkpoints & resume protocol
│   ├── messaging.md                    # Reliable text messaging & ACK delivery
│   ├── discovery.md                    # UDP multicast discovery beacons
│   ├── peers.md                        # Peer deduplication & tracking
│   ├── networking.md                   # TCP transport & socket handling
│   └── testing.md                      # Test suite documentation & Wireshark guide
├── scripts/                            # PowerShell automation scripts
│   ├── build.ps1                       # Compiles all Java sources with javac
│   ├── run.ps1                         # Launches single interactive node
│   ├── run_demo.ps1                    # Launches custom multi-node instance
│   ├── demo.ps1                        # Automated end-to-end multi-node live demo
│   ├── demo_resume.ps1                 # Automated transfer resume live demo
│   ├── create_demo_file.ps1            # Generates deterministic test files
│   ├── test.ps1                        # Executes all 75 automated test suites
│   └── clean.ps1                       # Purges compiled bytecode in out/
└── src/                                # Pure standard library source code
    ├── com/meshdrop/                   # Production application
    │   ├── Main.java                   # Application bootstrap & CLI launch
    │   ├── cli/                        # Interactive shell & command parser
    │   ├── connection/                 # Outbound connection manager & deduplication
    │   ├── core/                       # Node lifecycle, config, and identity
    │   ├── discovery/                  # UDP multicast discovery beacons
    │   ├── message/                    # Text messaging, delivery ACKs, ping/pong
    │   ├── network/                    # TCP server, connection handler, socket streams
    │   ├── peer/                       # Peer table, lifecycle states, duplicate resolution
    │   ├── protocol/                   # Binary packet encoder/decoder, handshake
    │   ├── security/                   # Ed25519 crypto, fingerprints, trust store
    │   ├── storage/                    # Directory layouts & path traversal defense
    │   ├── transfer/                   # File streaming, chunking, checkpoints, resume
    │   └── util/                       # Logging, byte manipulation, duration formatters
    └── test/com/meshdrop/              # Test suites & live demo runners
        ├── TestRunner.java             # Standalone assertion-based test runner
        └── demo/                       # Live demo runners (LiveDemoRunner, ResumeDemoRunner)
```
