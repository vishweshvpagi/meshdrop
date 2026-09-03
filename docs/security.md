# MeshDrop Security & Trust Architecture

MeshDrop incorporates a zero-external-dependency cryptographic security and identity trust layer designed to operate purely over local and ad-hoc networks using standard Java 26 cryptographic primitives (`java.security` and `java.security.spec`).

---

## 1. Cryptographic Node Identity

Every MeshDrop node generates or loads a persistent asymmetric cryptographic keypair upon initialization:
- **Algorithm**: `Ed25519` (Edwards-curve Digital Signature Algorithm, RFC 8032)
- **Key Storage**: `<dataDir>/identity/node_identity.properties`
  - Encoded in PKCS#8 (`PrivateKey`) and X.509 (`SubjectPublicKeyInfo`) formats in Base64
  - Permissions checked to prevent world-readable exposure
- **Identity Tuple**:
  - `nodeId`: Universally unique identifier (`java.util.UUID`)
  - `displayName`: Human-friendly label (UTF-8, 1-64 bytes)
  - `publicKey`: Ed25519 public key (44 bytes standard X.509)
  - `fingerprint`: Deterministic 32-character hex identifier

---

## 2. Deterministic Identity Fingerprints

To avoid presenting long raw cryptographic keys to users, MeshDrop derives a human-verifiable fingerprint from each public key:

$$\text{Fingerprint} = \text{HexFormat}(\text{SHA-256}(\text{PublicKey.getEncoded()})[0..15])$$

- Formatted into 8 groups of 4 uppercase hexadecimal characters separated by hyphens:
  `AB12-CD34-EF56-7890-1234-5678-90AB-CDEF`
- Deterministic, collision-resistant, and easily comparable across independent consoles out-of-band (e.g. verbally or via secure messenger).

---

## 3. Trust Model & Decisions

Peers are evaluated according to a three-state trust lifecycle:

| Trust Decision | Meaning | Network Behavior |
| :--- | :--- | :--- |
| `UNTRUSTED` | Default state for newly discovered peers | Messages and files are accepted, but marked with an untrusted indicator |
| `TRUSTED` | Explicitly verified by the user | Peer fingerprint is pinned; full participation |
| `BLOCKED` | Blacklisted by operator or due to MITM detection | All inbound/outbound packets dropped, connections immediately severed |

### Man-in-the-Middle (MITM) & Impersonation Defense
If a remote node claims an existing UUID but presents a public key with a fingerprint differing from the pinned trust store record, the `TrustStore` immediately marks the node as `BLOCKED` and raises a critical security warning:
```text
[SECURITY] Fingerprint mismatch for peer <UUID>! Known: <FP1>, Presented: <FP2>
```

---

## 4. Trust Store Persistence

The trust store is persisted to `<dataDir>/trust/trust_store.txt`.
- **Atomic File Replacement**: Updates are written to a `.tmp` staging file and renamed atomically (`StandardCopyOption.ATOMIC_MOVE`) to prevent corruption during unexpected crashes or power loss.
- **Format**: Pipe-delimited flat file (`<UUID>|<FINGERPRINT>|<STATE>|<NOTE>|<TIMESTAMP>`).

---

## 5. Path Traversal & Download Sandbox Defense

To prevent malicious remote peers from overwriting system files (e.g., sending `../../../../Windows/System32/evil.dll`), all received files are processed through `StorageManager.resolveSafeDownloadPath()`:
1. Strips all relative directory navigation elements (`..`, `.`, `/`, `\`).
2. Strips Windows drive designators (`C:`, `D:`).
3. Enforces that the resolved canonical path strictly starts with `StorageManager.getDownloadsDir()`.
4. Rejects empty, whitespace, or invalid file names with a `SecurityException`.
