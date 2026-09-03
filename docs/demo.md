# MeshDrop Demonstration Guide

MeshDrop includes automated and interactive multi-node demonstration scripts designed to showcase its real-time peer-to-peer capabilities on real TCP and UDP sockets under Windows PowerShell.

---

## 1. Automated End-to-End Live Demo

Executes a complete peer-to-peer interaction cycle between two independent `Node` instances running on local ports.

```powershell
.\scripts\demo.ps1
```

### What this script demonstrates:
1. **Cryptographic Identity**: Generates unique Ed25519 keypairs and human-readable fingerprints for nodes `Alice` and `Bob`.
2. **Dynamic Port Binding**: Starts TCP listening servers and UDP multicast discovery listeners on ephemeral ports.
3. **TCP Handshake**: Performs two-way binary `HELLO` and `HELLO_RESPONSE` packet exchanges with peer validation.
4. **Latency Measurement**: Executes application-level bidirectional ping over persistent TCP.
5. **Reliable Messaging**: Sends an application text payload and verifies receipt of the matching delivery ACK.
6. **High-Speed Chunked File Transfer**: Streams binary chunks across the socket into a sandboxed downloads directory.
7. **Cryptographic SHA-256 Integrity Verification**: Calculates and matches source vs destination file digests.
8. **Clean Shutdown**: Shuts down network sockets and threads in strict reverse-dependency order.

---

## 2. Automated Resumable File Transfer Demo

Demonstrates resilience against abrupt network severance and crash-safe partial transfer resumption.

```powershell
.\scripts\demo_resume.ps1
```

### What this script demonstrates:
1. **Pre-Transfer Hashing**: Creates a multi-chunk binary archive and computes the authorative source SHA-256 digest.
2. **Active Streaming Severance**: Intentionally interrupts the TCP transport mid-transfer.
3. **Crash-Safe Checkpoint Verification**: Inspects the staging directory to confirm that the `.part` file and `.meta` checkpoint file were atomically flushed to disk.
4. **Peer Reconnection**: Restores TCP session and completes peer handshake.
5. **Receiver-Authoritative Resume**: Exchanges `FILE_RESUME_REQUEST` and `FILE_RESUME_RESPONSE`. Sender validates the receiver's byte offset and seeks to the exact chunk boundary.
6. **Resumed Chunk Delivery**: Transfers remaining chunks without duplicating already-committed data.
7. **Final Verification**: Computes destination SHA-256 and confirms match with original source.

---

## 3. Manual Multi-Terminal Demonstration

To interactively pilot two nodes from separate terminal windows:

### Terminal 1 (Alice):
```powershell
.\scripts\run_demo.ps1 -Name Alice -TcpPort 5001 -DiscoveryPort 6001
```

### Terminal 2 (Bob):
```powershell
.\scripts\run_demo.ps1 -Name Bob -TcpPort 5002 -DiscoveryPort 6002
```

### In Terminal 1 (Alice CLI):
```text
meshdrop> peers
meshdrop> ping Bob
meshdrop> send Bob "Hello from Alice!"
meshdrop> sendfile Bob sample.txt
meshdrop> trust Bob
meshdrop> status
```

### Available Demo Utilities:
- **`.\scripts\create_demo_file.ps1 -Path "sample.bin" -SizeKB 1024`**: Generates a 1 MB test file and outputs its SHA-256.
- **`.\scripts\test.ps1`**: Executes all 75 automated test suites.
