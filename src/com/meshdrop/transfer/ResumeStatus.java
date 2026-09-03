package com.meshdrop.transfer;

/**
 * Status outcomes for a file transfer resume negotiation.
 */
public enum ResumeStatus {
    RESUME_ACCEPTED((byte) 0x01),
    RESUME_NOT_FOUND((byte) 0x02),
    RESUME_INVALID((byte) 0x03),
    RESUME_HASH_MISMATCH((byte) 0x04),
    RESUME_METADATA_MISMATCH((byte) 0x05),
    RESUME_COMPLETE((byte) 0x06),
    RESUME_REJECTED((byte) 0x07);

    private final byte code;

    ResumeStatus(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static ResumeStatus fromCode(byte code) {
        for (ResumeStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown resume status code: 0x" + Integer.toHexString(code & 0xFF));
    }
}
