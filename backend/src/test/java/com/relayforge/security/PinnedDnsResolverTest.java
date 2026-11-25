package com.relayforge.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PinnedDnsResolverTest {

    @Test
    void concurrentRequestsKeepHostPinnedUntilBothFinish() throws Exception {
        PinnedDnsResolver resolver = new PinnedDnsResolver();
        InetAddress address = InetAddress.getByName("93.184.215.14");

        resolver.pin("example.com", List.of(address));
        resolver.pin("EXAMPLE.COM", List.of(address));
        resolver.unpin("example.com");

        assertArrayEquals(new InetAddress[] {address}, resolver.resolve("example.com"));
        assertEquals(1, resolver.pinnedHostCount());

        resolver.unpin("example.com");
        assertThrows(UnknownHostException.class, () -> resolver.resolve("example.com"));
    }
}
