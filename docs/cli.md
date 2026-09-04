# MeshDrop Interactive CLI Specification

## 1. Overview
The MeshDrop Interactive Command-Line Interface (CLI) provides human operators with direct control, inspection, and messaging capabilities for a MeshDrop node.

Crucially, **the CLI is a control layer, not a networking layer**. The CLI contains zero socket management, protocol encoding, handshake negotiation, or discovery logic. It delegates all operations to `Node` and its underlying services (`PeerManager`, `DiscoveryService`, `MessageService`, `PingService`, `ConnectionManager`).

---

## 2. CLI Architecture

```text
                    CommandLineInterface
                             │
                             ▼
                            Node
                             │
       ┌─────────────┬───────┴───────┬──────────────┐
       ▼             ▼               ▼              ▼
 PeerManager   MessageService   PingService   DiscoveryService
       │             │               │
       └─────────────┼───────────────┘
                     ▼
               TcpConnection
                     │
              Binary Protocol
```

### Threading Model
- **Interactive Input**: Runs on the main application thread, blocking synchronously on `System.in` / `BufferedReader.readLine()`.
- **Asynchronous Networking**: UDP multicast discovery, TCP server accepts, outbound connection attempts, receiver loops, and handshake timers all run on Java 26 virtual threads (`Thread.ofVirtual()`).
- **Synchronized Console Output**: `Logger.console()` and `Logger.consolePrint()` are guarded by a shared monitor lock to prevent interleaved characters or lines when incoming messages arrive while the operator is typing.

---

## 3. Command Syntax and Available Commands

### Prompt
```text
meshdrop> 
```

### Command Reference

| Command | Syntax | Description |
| :--- | :--- | :--- |
| `help` | `help` | Displays available commands and their syntax. |
| `status` | `status` | Displays node state, identity, TCP/UDP ports, peer counts, and connection counts. |
| `info` | `info` | Displays node identity and configuration settings. |
| `peers` | `peers` | Renders a table of known peers (ID, Name, Address, State, Trust). |
| `connections` | `connections` | Lists all active TCP connections (Remote name, ID, Remote address, State). |
| `connect` | `connect <host> [port]` | Directly establishes an outbound TCP connection to a peer. |
| `discover` | `discover` | Displays UDP discovery status and broadcasts an announcement beacon. |
| `send` | `send <peer> <message>` | Transmits a `MESSAGE` packet to a connected peer. |
| `sendfile` | `sendfile <peer> <path>` | Transmits a file using streaming sliding-window flow control. |
| `autoaccept`| `autoaccept [on\|off]` | Toggles auto-acceptance of incoming file transfer offers. |
| `downloads` | `downloads [open]` | Views downloads directory or opens it in File Explorer. |
| `transfers` | `transfers` | Lists active and past file transfers with status and speed. |
| `transfer-debug` | `transfer-debug <id>` | Shows granular sliding-window, retry, and checkpoint metrics. |
| `resume` | `resume <transferId>` | Resumes an interrupted transfer from on-disk checkpoint. |
| `cancel` | `cancel <transferId>` | Cancels an active or interrupted transfer. |
| `ping` | `ping <peer>` | Measures round-trip application latency using `PING`/`PONG` frames. |
| `trust` | `trust <peer>` | Explicitly trusts a peer's cryptographic identity fingerprint. |
| `untrust` | `untrust <peer>` | Sets a peer's trust status back to untrusted. |
| `block` | `block <peer>` | Blocks a peer, refusing incoming connections and offers. |
| `clear` | `clear` | Clears the console using `cmd /c cls` or ANSI escape sequences. |
| `exit` / `quit` | `exit` or `quit` | Gracefully shuts down CLI and initiates node shutdown. |

---

## 4. Command Details and Behaviors

### 4.1 `help`
Displays supported commands:
```text
meshdrop> help

MeshDrop commands:

------------------------------------------------
MeshDrop Commands
------------------------------------------------
peers                   List discovered peers
connections             Show TCP connections
connect <host> [port]   Connect to a peer directly
status                  Show node status
info                    Show local identity
discover                Run peer discovery
send <peer> <message>   Send a message
sendfile <peer> <path>  Send a file
autoaccept [on|off]     Toggle auto-accept for incoming files
downloads [open]        View downloads folder or open in Explorer
transfers               Show transfers
transfer-debug <id>     Show debug metrics for a transfer
resume <transferId>     Resume transfer
cancel <transferId>     Cancel transfer
ping <peer>             Ping peer
trust <peer>            Trust peer identity
untrust <peer>          Untrust peer identity
block <peer>            Block peer
clear                   Clear terminal
exit                    Shutdown MeshDrop
------------------------------------------------
```

### 4.2 `status`
Displays runtime status derived directly from authoritative subsystem states:
```text
meshdrop> status

Node Status
-----------
State:       RUNNING
Identity:    3733334c-b6d2-4411-9c66-0017a72c69ab
Name:        Vishwesh-PC
Peers:       2
Connections: 1
Discovery:   RUNNING
```

### 4.3 `info`
Shows node identity and network parameters:
```text
meshdrop> info

Local Node
----------
Name:               Vishwesh-PC
ID:                 3733334c-b6d2-4411-9c66-0017a72c69ab
TCP Port:           5000
UDP Discovery Port: 5001
State:              RUNNING
```

### 4.4 `peers`
Renders all known peers from `PeerManager`:
```text
meshdrop> peers

Known Peers
-----------

1. Alice-PC
   ID:      ea9ed503-9ca9-4aab-9610-837611d791e2
   Address: 192.168.1.10:5000
   State:   CONNECTED

2. Bob-PC
   ID:      5827bc66-d4cd-4c0d-a082-00a5814e9668
   Address: 192.168.1.12:5000
   State:   DISCOVERED
```
If no peers exist: `No peers discovered.`

### 4.5 `connections`
Inspects active low-level transport connections:
```text
meshdrop> connections

Active Connections
------------------

1. Alice-PC
   ID:     ea9ed503-9ca9-4aab-9610-837611d791e2
   Remote: 192.168.1.10:5000
   State:  READY
```
If no connections exist: `No active connections.`

### 4.6 `discover`
Queries `DiscoveryService` and triggers an immediate announcement:
```text
meshdrop> discover
Starting LAN discovery...

Discovery service: RUNNING
Multicast group:   239.255.77.80:5001
Discovery beacon broadcasted.
Known peers:       2
```

### 4.7 `send <peer> <message>`
Sends a binary protocol `MESSAGE` packet to a peer.

#### Flexible Peer Lookup
The `<peer>` argument can be:
1. Exact full UUID: `ea9ed503-9ca9-4aab-9610-837611d791e2`
2. Unique UUID prefix: `ea9e` (matches if unambiguous)
3. Display name: `Alice-PC` (case-insensitive)

If ambiguous:
```text
meshdrop> send laptop hello
Error: multiple peers match 'laptop':
  ea9ed503-9ca9-4aab-9610-837611d791e2  Laptop-A
  b7b6e4f9-334a-4d54-b25d-f38e853539c0  Laptop-B
Use a longer ID to be more specific.
```

If peer is not found: `Error: peer not found`
If peer is not connected: `Error: peer is not connected`
If missing arguments: `Usage: send <peer> <message>`

#### Message Argument Parsing
Arguments with spaces, double quotes, or single quotes are handled cleanly:
```text
meshdrop> send Alice-PC hello from MeshDrop
Message sent to Alice-PC.

meshdrop> send Alice-PC "hello from my computer"
Message sent to Alice-PC.

meshdrop> send Alice-PC 'hello from my computer'
Message sent to Alice-PC.
```

### 4.8 Incoming Messages
Incoming messages arrive asynchronously on virtual threads and are dispatched via `MessageService` to registered `MessageListener`s:
```text
[Message] Alice-PC: Hey, got your message!
meshdrop> 
```
Output synchronization ensures the prompt and message never interleave.

### 4.9 `ping <peer>`
Measures round-trip application latency using `PING` and `PONG` frames:
```text
meshdrop> ping Alice-PC
Pinging Alice-PC...

Response received.

Latency: 4 ms
```
If timeout occurs (5000 ms default): `Error: request timed out`

### 4.10 `sendfile <peer> <path>`
Transmits a local file to a remote peer using streaming sliding-window flow control:
```text
meshdrop> sendfile Alice "C:\movies\sample.mp4"
Preparing file...
Sending sample.mp4
Size: 500.0 MB
SHA-256: 4f8a...
Waiting for Alice to accept...
[TRANSFER] Accepted by Alice. Streaming data...
[====================] 100.0% | 500.0 MB / 500.0 MB |  54.2 MB/s | ETA: 00:00 | TX-0214D6
Transfer completed.
```

### 4.11 `autoaccept [on|off]`
Enables or disables automatic acceptance of incoming file offers:
```text
meshdrop> autoaccept on
Auto-accept enabled for incoming file transfers.
```

### 4.12 `downloads [open]`
Lists the contents of the local downloads directory or opens it in File Explorer:
```text
meshdrop> downloads
Downloads Directory: C:\Users\VBP\Downloads\MeshDrop
- presentation.pdf (14.2 MB)
- sample.mp4 (500.0 MB)
```

### 4.13 `transfers`
Displays all active, completed, and interrupted transfers:
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

### 4.14 `transfer-debug <id>`
Inspects real-time sliding window, retry counters, in-flight packet counts, and checkpoint state:
```text
meshdrop> transfer-debug 60866179-3181-48f3-b197-130d8e8d79cf
```

### 4.15 `resume <transferId>`
Resumes an interrupted transfer from its local on-disk checkpoint without re-sending previously verified chunks:
```text
meshdrop> resume 0214d6a2-a77b-47e1-ab21-cc8e7c3017b6
Resuming transfer...
Progress: [=======             ] 35.8% (Resumed from chunk 5)
```

### 4.16 `cancel <transferId>`
Cancels an active or paused transfer, notifying the remote peer and cleaning up resources.

### 4.17 `trust <peer>` / `untrust <peer>` / `block <peer>`
Manages peer trust levels based on cryptographic fingerprints:
```text
meshdrop> trust Alice
Peer Alice is now TRUSTED.
```

### 4.18 `exit` / `quit`
Initiates graceful shutdown:
```text
meshdrop> exit
Shutting down MeshDrop...
```
Stops CLI, stops `DiscoveryService`, stops `ConnectionManager`, stops `TcpServer`, closes active connections, and exits the JVM cleanly.
