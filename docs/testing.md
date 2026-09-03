# MeshDrop Testing Guide

## 1. Automated Test Suite
Because MeshDrop avoids third-party dependencies (like JUnit), tests are written using standard Java assertions and executed by a custom `TestRunner` class.

### Running Automated Tests
```powershell
.\scripts\test.ps1
```
This script compiles all sources under `src/com` and `src/test`, then executes `java -enableassertions -cp out com.meshdrop.TestRunner`.

---

## 2. Test Structure
- **`PacketEncoderTest` / `PacketDecoderTest`**: Unit tests verifying binary encoding, boundary framing, fragmentation, and corrupted packets.
- **`TcpServerTest` / `TcpConnectionTest`**: Verifies socket binding, connection handshakes, and concurrent I/O.
- **`PeerManagerTest`**: Verifies peer registration, deduplication, and status transitions.
- **`ChunkManagerTest` / `HashUtilsTest` / `TransferTest`**: Verifies chunk calculation, partial chunks, SHA-256 streaming verification, and resume logic.

---

## 3. LAN Testing & Windows Firewall Configuration

### Checking Port Reachability
From Node B, test connectivity to Node A:
```powershell
Test-NetConnection -ComputerName 192.168.1.10 -Port 5000
```

### Allowing Port 5000 Through Windows Firewall
Run in an elevated PowerShell prompt:
```powershell
New-NetFirewallRule -DisplayName "MeshDrop TCP" -Direction Inbound -LocalPort 5000 -Protocol TCP -Action Allow
New-NetFirewallRule -DisplayName "MeshDrop UDP" -Direction Inbound -LocalPort 5001 -Protocol UDP -Action Allow
```

---

## 4. Packet Inspection with Wireshark
- **Filter for TCP traffic**: `tcp.port == 5000`
- **Filter for UDP discovery**: `udp.port == 5001`
- **Analyze Flow**:
  1. TCP Handshake (`SYN` -> `SYN-ACK` -> `ACK`)
  2. MeshDrop `HELLO` packet (`0x4D 0x44 0x52 0x50 0x01 0x01 ...`)
  3. MeshDrop `HELLO_RESPONSE` packet
  4. Periodic `PING` / `PONG`
  5. File chunks during transfer
