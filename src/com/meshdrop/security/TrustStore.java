package com.meshdrop.security;

import com.meshdrop.util.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages trusted, untrusted, and blocked peer identity records with crash-safe disk persistence.
 */
public class TrustStore {

    public record TrustEntry(
            UUID peerId,
            IdentityFingerprint fingerprint,
            TrustDecision decision,
            String alias,
            long timestamp
    ) {}

    private final Path storeFile;
    private final Map<UUID, TrustEntry> entries = new ConcurrentHashMap<>();

    public TrustStore(Path trustDir) {
        Objects.requireNonNull(trustDir, "trustDir must not be null");
        this.storeFile = trustDir.resolve("trust_store.txt");
        load();
    }

    /**
     * Determines trust decision for a given peer and public key fingerprint.
     */
    public TrustDecision getTrustDecision(UUID peerId, IdentityFingerprint fingerprint) {
        if (peerId == null) return TrustDecision.UNTRUSTED;

        TrustEntry entry = entries.get(peerId);
        if (entry == null) {
            return TrustDecision.UNTRUSTED;
        }

        // If peer exists but fingerprint changed, flag potential identity spoofing
        if (fingerprint != null && entry.fingerprint() != null && !entry.fingerprint().equals(fingerprint)) {
            Logger.warn("[SECURITY] Fingerprint mismatch for peer " + peerId +
                    "! Known: " + entry.fingerprint() + ", Presented: " + fingerprint);
            return TrustDecision.BLOCKED;
        }

        return entry.decision();
    }

    public boolean isTrusted(UUID peerId, IdentityFingerprint fingerprint) {
        return getTrustDecision(peerId, fingerprint) == TrustDecision.TRUSTED;
    }

    public boolean isBlocked(UUID peerId) {
        TrustEntry entry = entries.get(peerId);
        return entry != null && entry.decision() == TrustDecision.BLOCKED;
    }

    public synchronized void trust(UUID peerId, IdentityFingerprint fingerprint, String alias) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        TrustEntry entry = new TrustEntry(peerId, fingerprint, TrustDecision.TRUSTED, alias, System.currentTimeMillis());
        entries.put(peerId, entry);
        save();
        Logger.info("[TRUST] Peer " + peerId + " (" + (alias != null ? alias : "unknown") + ") is now TRUSTED");
    }

    public synchronized void untrust(UUID peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        TrustEntry existing = entries.remove(peerId);
        save();
        if (existing != null) {
            Logger.info("[TRUST] Peer " + peerId + " set to UNTRUSTED (removed from trust store)");
        }
    }

    public synchronized void block(UUID peerId, IdentityFingerprint fingerprint, String reason) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        TrustEntry entry = new TrustEntry(peerId, fingerprint, TrustDecision.BLOCKED, reason, System.currentTimeMillis());
        entries.put(peerId, entry);
        save();
        Logger.warn("[SECURITY] Peer " + peerId + " is now BLOCKED: " + reason);
    }

    public Optional<TrustEntry> getEntry(UUID peerId) {
        return Optional.ofNullable(entries.get(peerId));
    }

    public List<TrustEntry> listAll() {
        return new ArrayList<>(entries.values());
    }

    /**
     * Loads trust entries from disk.
     */
    public synchronized void load() {
        entries.clear();
        if (!Files.isRegularFile(storeFile)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(storeFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\|", -1);
                if (parts.length >= 5) {
                    try {
                        UUID peerId = UUID.fromString(parts[0]);
                        IdentityFingerprint fp = !parts[1].isBlank() ? IdentityFingerprint.parse(parts[1]) : null;
                        TrustDecision decision = TrustDecision.fromString(parts[2]);
                        String alias = parts[3];
                        long ts = Long.parseLong(parts[4]);

                        entries.put(peerId, new TrustEntry(peerId, fp, decision, alias, ts));
                    } catch (Exception e) {
                        Logger.warn("[TRUST] Skipping corrupt line in trust store: " + line);
                    }
                }
            }
        } catch (IOException e) {
            Logger.warn("[TRUST] Failed to read trust store from " + storeFile + ": " + e.getMessage());
        }
    }

    /**
     * Atomically saves trust entries to disk.
     */
    public synchronized void save() {
        try {
            if (storeFile.getParent() != null) {
                Files.createDirectories(storeFile.getParent());
            }

            Path tmpFile = storeFile.resolveSibling(storeFile.getFileName().toString() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(tmpFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC)) {

                writer.write("# MeshDrop Peer Trust Store\n");
                writer.write("# format: peerId|fingerprint|decision|alias|timestamp\n");
                for (TrustEntry entry : entries.values()) {
                    writer.write(entry.peerId() + "|" +
                            (entry.fingerprint() != null ? entry.fingerprint().formatted() : "") + "|" +
                            entry.decision().name() + "|" +
                            (entry.alias() != null ? entry.alias().replace("|", "_") : "") + "|" +
                            entry.timestamp() + "\n");
                }
            }

            try {
                Files.move(tmpFile, storeFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmpFile, storeFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Logger.severe("[TRUST] Failed to persist trust store to " + storeFile, e);
        }
    }
}
