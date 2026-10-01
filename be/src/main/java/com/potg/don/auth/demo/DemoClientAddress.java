package com.potg.don.auth.demo;

import java.util.Arrays;

/** Opaque, memory-only IPv4 address or IPv6 /64 bucket. Parsing never performs DNS. */
public final class DemoClientAddress {
    private final byte[] key;
    private DemoClientAddress(byte[] key) { this.key = key; }

    public static DemoClientAddress parse(String literal) {
        if (literal == null || literal.isEmpty() || literal.length() > 45
            || !literal.matches("[0-9a-fA-F:.]+")) throw invalid();
        if (!literal.contains(":")) return new DemoClientAddress(ipv4(literal));
        byte[] address = ipv6(literal);
        boolean mapped = true;
        for (int i = 0; i < 10; i++) mapped &= address[i] == 0;
        mapped &= address[10] == (byte) 0xff && address[11] == (byte) 0xff;
        return new DemoClientAddress(mapped ? Arrays.copyOfRange(address, 12, 16) : Arrays.copyOf(address, 8));
    }

    private static byte[] ipv4(String literal) {
        String[] octets = literal.split("\\.", -1);
        if (octets.length != 4) throw invalid();
        byte[] bytes = new byte[4];
        for (int i = 0; i < octets.length; i++) {
            String octet = octets[i];
            if (!octet.matches("0|[1-9][0-9]{0,2}")) throw invalid();
            int value = Integer.parseInt(octet);
            if (value > 255) throw invalid();
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static byte[] ipv6(String literal) {
        String input = literal;
        if (input.contains(".")) {
            int colon = input.lastIndexOf(':');
            if (colon < 0) throw invalid();
            byte[] last = ipv4(input.substring(colon + 1));
            input = input.substring(0, colon + 1)
                + Integer.toHexString((last[0] & 255) * 256 + (last[1] & 255)) + ":"
                + Integer.toHexString((last[2] & 255) * 256 + (last[3] & 255));
        }
        int compression = input.indexOf("::");
        if (compression != input.lastIndexOf("::")) throw invalid();
        String[] left = groups(compression < 0 ? input : input.substring(0, compression));
        String[] right = compression < 0 ? new String[0] : groups(input.substring(compression + 2));
        int size = left.length + right.length;
        if (compression < 0 ? size != 8 : size >= 8) throw invalid();
        byte[] result = new byte[16];
        for (int i = 0; i < left.length; i++) group(result, i, left[i]);
        for (int i = 0; i < right.length; i++) group(result, 8 - right.length + i, right[i]);
        return result;
    }

    private static String[] groups(String part) { return part.isEmpty() ? new String[0] : part.split(":", -1); }
    private static void group(byte[] bytes, int index, String group) {
        if (!group.matches("[0-9a-fA-F]{1,4}")) throw invalid();
        int value = Integer.parseInt(group, 16);
        bytes[index * 2] = (byte) (value >>> 8);
        bytes[index * 2 + 1] = (byte) value;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("DEMO_CLIENT_ADDRESS_REJECTED"); }
    @Override public boolean equals(Object other) { return other instanceof DemoClientAddress address && Arrays.equals(key, address.key); }
    @Override public int hashCode() { return Arrays.hashCode(key); }
    @Override public String toString() { return "DemoClientAddress[redacted]"; }
}
