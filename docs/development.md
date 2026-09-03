# MeshDrop Development Guide

## 1. Development Principles
- **Standard Library Only**: No external frameworks or dependency managers (no Maven, Gradle, Netty).
- **Strict Framing**: Never assume one socket `read()` equals one message.
- **Streaming I/O**: Never load large files entirely into RAM.
- **Thread Safety**: Virtual threads for I/O, explicit locks/concurrency primitives for state management.
- **Fail-Safe**: Malformed remote packets should drop the connection, never crash the node.

---

## 2. Directory Layout
```text
MeshDrop/
├── docs/           # Specifications, protocol, networking, and testing guides
├── scripts/        # PowerShell build, run, clean, and test scripts
├── src/            # Application source (com/meshdrop) and test source (test/com/meshdrop)
├── storage/        # File downloads, temporary chunk storage, and identity files
└── out/            # Compiled .class files
```

---

## 3. Extending MeshDrop

### 3.1 Adding a New PacketType
1. Add constant to `com.meshdrop.protocol.PacketType`.
2. Update `docs/protocol.md` with binary payload specification.
3. Add encoding/decoding handlers in `PacketEncoder` and `PacketDecoder`.
4. Add dispatcher handling in `TcpConnectionHandler` or relevant service.
5. Add unit test coverage in `PacketEncoderTest` and `PacketDecoderTest`.

### 3.2 Adding a New CLI Command
1. Define command syntax in `com.meshdrop.cli.Command`.
2. Implement parsing in `CommandParser`.
3. Register command execution handler in `CommandLineInterface`.
4. Update `help` text.

---

## 4. Phase-by-Phase Roadmap
1. Phase 0: Project Initialization
2. Phase 1: Basic TCP Server
3. Phase 2: TCP Client
4. Phase 3: Bidirectional Communication
5. Phase 4: Custom Binary Protocol
6. Phase 5: Packet Decoder
7. Phase 6: Hello Handshake & Identity
8. Phase 7: Peer Management
9. Phase 8: UDP Peer Discovery
10. Phase 9: Interactive CLI
11. Phase 10: P2P Messaging
12. Phase 11: Heartbeats
13. Phase 12: File Transfer Engine
14. Phase 13: Chunk Management
15. Phase 14: File Integrity (SHA-256)
16. Phase 15: Transfer Progress
17. Phase 16: Multiple Concurrent Transfers
18. Phase 17: Resumable Transfers
19. Phase 18: Transfer State Machine
20. Phase 19: Error Handling & Resilience
21. Phase 20: Security & Validation
22. Phase 21: Graceful Shutdown
23. Phase 22: Logging Subsystem
24. Phase 23: Comprehensive Automated Unit Tests
25. Phase 24: Integration Tests
26. Phase 25: Multi-machine LAN Verification
27. Phase 26: Wireshark Packet Inspection
28. Phase 27: Full Documentation Finalization
