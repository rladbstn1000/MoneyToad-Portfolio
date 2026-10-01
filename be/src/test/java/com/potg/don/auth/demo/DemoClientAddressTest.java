package com.potg.don.auth.demo;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DemoClientAddressTest {
    @Test void ipv4AndMappedRepresentationsAreEqual() {
        var address = DemoClientAddress.parse("192.0.2.1");
        assertEquals(address, DemoClientAddress.parse("::ffff:192.0.2.1"));
        assertEquals(address, DemoClientAddress.parse("0:0:0:0:0:ffff:c000:201"));
        assertNotEquals(address, DemoClientAddress.parse("192.0.2.2"));
        assertEquals("DemoClientAddress[redacted]", address.toString());
    }

    @Test void ipv6NormalizesEquivalentTextAndGroupsOnlySamePrefix() {
        assertEquals(DemoClientAddress.parse("2001:DB8:1:2::1"),
            DemoClientAddress.parse("2001:0db8:0001:0002:ffff:ffff:ffff:ffff"));
        assertNotEquals(DemoClientAddress.parse("2001:db8:1:2::1"), DemoClientAddress.parse("2001:db8:1:3::1"));
        assertEquals(DemoClientAddress.parse("::"), DemoClientAddress.parse("0:0:0:0:0:0:0:1"));
    }

    @Test void nullIsRejectedWithoutEcho() {
        assertEquals("DEMO_CLIENT_ADDRESS_REJECTED", assertThrows(IllegalArgumentException.class,
            () -> DemoClientAddress.parse(null)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "host.invalid", "192.0.2.1:80", "[::1]:80", "[::1]", "::1/64", "fe80::1%en0",
        "192.0.2.1,192.0.2.2", " 192.0.2.1", "192.0.2.1 ", "192.0.2", "256.0.2.1", "0192.0.2.1",
        "192.00.2.1", "0xc0.0.2.1", "3221225985", ":", ":::1", "1::2::3", "1:2:3:4:5:6:7",
        "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::", "12345::", "::ffff:192.0.2.999", "::ffff:192.00.2.1",
        "::ffff:192.0.2.1:2", "::g", "\r\n192.0.2.1"})
    void invalidLiteralNeverUsesResolver(String input) {
        assertEquals("DEMO_CLIENT_ADDRESS_REJECTED", assertThrows(IllegalArgumentException.class,
            () -> DemoClientAddress.parse(input)).getMessage());
    }
}
