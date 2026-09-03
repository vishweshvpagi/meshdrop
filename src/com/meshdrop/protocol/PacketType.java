package com.meshdrop.protocol;

/**
 * Enumeration of all supported binary protocol packet types with explicit numeric wire codes.
 */
public enum PacketType {
    HELLO((byte) 0x01),
    HELLO_RESPONSE((byte) 0x02),
    PING((byte) 0x03),
    PONG((byte) 0x04),
    MESSAGE((byte) 0x05),
    DISCOVER((byte) 0x06),
    DISCOVER_RESPONSE((byte) 0x07),
    MESSAGE_ACK((byte) 0x08),
    FILE_OFFER((byte) 0x10),
    FILE_CHUNK((byte) 0x11),
    FILE_COMPLETE((byte) 0x12),
    FILE_RESUME_REQUEST((byte) 0x13),
    FILE_RESUME_RESPONSE((byte) 0x14),
    FILE_ACCEPT((byte) 0x15),
    FILE_REJECT((byte) 0x16),
    FILE_ACK((byte) 0x17),
    FILE_ERROR((byte) 0x18),
    ERROR((byte) 0xFF);

    private final byte code;

    PacketType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    /**
     * Resolves a PacketType by its wire code.
     *
     * @param code numeric wire byte code
     * @return matching PacketType
     * @throws IllegalArgumentException if the code is unknown
     */
    public static PacketType fromCode(byte code) {
        for (PacketType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown packet type code: 0x" + Integer.toHexString(code & 0xFF));
    }
}
