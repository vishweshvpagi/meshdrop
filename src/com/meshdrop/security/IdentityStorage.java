package com.meshdrop.security;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.util.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/**
 * Persists and loads local node identity and Ed25519 cryptographic keypairs.
 *
 * Stored within the local application storage directory (e.g. data/identity/node_identity.properties).
 * Private key material is saved with restricted read/write permissions.
 */
public class IdentityStorage {

    public record LoadedIdentity(
            NodeIdentity identity,
            KeyPair keyPair
    ) {}

    private final Path identityDir;
    private final Path identityFile;

    public IdentityStorage(Path identityDir) {
        this.identityDir = Objects.requireNonNull(identityDir, "identityDir must not be null");
        this.identityFile = identityDir.resolve("node_identity.properties");
    }

    /**
     * Loads existing identity from disk or generates and saves a new persistent Ed25519 identity.
     */
    public LoadedIdentity loadOrCreate(String preferredDisplayName) throws IOException {
        Files.createDirectories(identityDir);

        if (Files.isRegularFile(identityFile)) {
            try {
                return load();
            } catch (Exception e) {
                Logger.warn("[SECURITY] Failed to load existing identity from " + identityFile +
                        ", generating fresh identity: " + e.getMessage());
            }
        }

        // Generate new persistent identity
        UUID nodeId = UUID.randomUUID();
        String name = preferredDisplayName != null && !preferredDisplayName.isBlank()
                ? preferredDisplayName
                : "MeshDrop-" + nodeId.toString().substring(0, 4).toUpperCase();

        KeyPair keyPair = CryptoUtils.generateEd25519KeyPair();
        NodeIdentity identity = new NodeIdentity(nodeId, name, keyPair.getPublic(), Instant.now());

        save(identity, keyPair);
        return new LoadedIdentity(identity, keyPair);
    }

    public LoadedIdentity load() throws Exception {
        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(identityFile, StandardCharsets.UTF_8)) {
            props.load(reader);
        }

        UUID nodeId = UUID.fromString(props.getProperty("nodeId"));
        String displayName = props.getProperty("displayName");
        Instant createdAt = Instant.parse(props.getProperty("createdAt", Instant.now().toString()));

        byte[] pubBytes = Base64.getDecoder().decode(props.getProperty("publicKey"));
        byte[] privBytes = Base64.getDecoder().decode(props.getProperty("privateKey"));

        PublicKey pubKey = CryptoUtils.decodePublicKey(pubBytes);
        PrivateKey privKey = CryptoUtils.decodePrivateKey(privBytes);
        KeyPair kp = new KeyPair(pubKey, privKey);

        NodeIdentity identity = new NodeIdentity(nodeId, displayName, pubKey, createdAt);
        return new LoadedIdentity(identity, kp);
    }

    public void save(NodeIdentity identity, KeyPair keyPair) throws IOException {
        Files.createDirectories(identityDir);

        Properties props = new Properties();
        props.setProperty("nodeId", identity.nodeId().toString());
        props.setProperty("displayName", identity.displayName());
        props.setProperty("createdAt", identity.createdAt().toString());
        props.setProperty("publicKey", Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        props.setProperty("privateKey", Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        if (identity.fingerprint() != null) {
            props.setProperty("fingerprint", identity.fingerprint().formatted());
        }

        Path tmpFile = identityDir.resolve("node_identity.properties.tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tmpFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC)) {
            props.store(writer, "MeshDrop Node Cryptographic Identity - DO NOT SHARE PRIVATE KEY");
        }

        // Restrict file permissions where supported
        protectFile(tmpFile.toFile());

        try {
            Files.move(tmpFile, identityFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmpFile, identityFile, StandardCopyOption.REPLACE_EXISTING);
        }

        protectFile(identityFile.toFile());
        Logger.info("[SECURITY] Persistent cryptographic identity saved to " + identityFile);
    }

    private void protectFile(File file) {
        try {
            file.setReadable(false, false);
            file.setReadable(true, true); // Owner only
            file.setWritable(false, false);
            file.setWritable(true, true); // Owner only
        } catch (Exception ignored) {}
    }
}
