package com.potg.don.auth.demo;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class DemoAbuseLimiterTest {
    private static final long SECOND = 1_000_000_000L;
    private final AtomicLong now = new AtomicLong();
    private final DemoAbuseLimiter limiter = new DemoAbuseLimiter(now::get);
    private static DemoClientAddress address(int i) { return DemoClientAddress.parse("192.0." + (i / 256) + "." + (i % 256)); }

    @Test void shortWindowHasExactBoundaryAndRejectedRequestDoesNotExtendIt() {
        for (int i = 0; i < 5; i++) assertTrue(limiter.tryLogin(address(i)).allowed());
        assertEquals(60, limiter.tryLogin(address(9)).retryAfterSeconds());
        assertEquals(5, limiter.addressCount());
        now.set(60 * SECOND - 1);
        assertEquals(1, limiter.tryLogin(address(9)).retryAfterSeconds());
        now.incrementAndGet();
        assertTrue(limiter.tryLogin(address(9)).allowed());
    }

    @Test void ipTenInThirtyMinutesAndLongestWaitWins() {
        for (int group = 0; group < 2; group++) {
            now.set(group * 60 * SECOND);
            for (int i = 0; i < 5; i++) assertTrue(limiter.tryLogin(address(1)).allowed());
        }
        assertEquals(1740, limiter.tryLogin(address(1)).retryAfterSeconds());
        now.set(120 * SECOND);
        assertEquals(1680, limiter.tryLogin(address(1)).retryAfterSeconds());
        now.set(1800 * SECOND);
        assertTrue(limiter.tryLogin(address(1)).allowed());
    }

    @Test void globalThirtyPerHourAcrossDifferentAddresses() {
        for (int group = 0; group < 6; group++) {
            now.set(group * 60 * SECOND);
            for (int i = 0; i < 5; i++) assertTrue(limiter.tryLogin(address(group * 5 + i)).allowed());
        }
        now.set(360 * SECOND);
        assertEquals(3240, limiter.tryLogin(address(99)).retryAfterSeconds());
        now.set(3600 * SECOND);
        assertTrue(limiter.tryLogin(address(99)).allowed());
    }

    @Test void readinessSixtyPerMinuteIsIndependentFromLogin() {
        for (int i = 0; i < 60; i++) assertTrue(limiter.tryReadiness().allowed());
        assertEquals(60, limiter.tryReadiness().retryAfterSeconds());
        assertTrue(limiter.tryLogin(address(1)).allowed());
        now.set(60 * SECOND - 1);
        assertFalse(limiter.tryReadiness().allowed());
        now.incrementAndGet();
        assertTrue(limiter.tryReadiness().allowed());
    }

    @Test void concurrentLoginRequestsCannotOverrunGlobalWindow() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 80; i++) {
                final int id = i;
                results.add(pool.submit(() -> { start.await(); return limiter.tryLogin(address(id)).allowed(); }));
            }
            start.countDown();
            int allowed = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) allowed++;
            assertEquals(5, allowed);
            assertEquals(5, limiter.addressCount());
        }
    }

    @Test void saturatedMapDoesNotEvictActiveEntriesOrExtendRejectedEntryLifetime() {
        var broadGlobal = new DemoAbuseLimiter.Limits(10, Duration.ofMinutes(30).toNanos(),
            2000, 60 * SECOND, 2000, 3600 * SECOND, 1024, 60, 60 * SECOND);
        var mapLimiter = new DemoAbuseLimiter(now::get, broadGlobal);
        for (int i = 0; i < 1024; i++) assertTrue(mapLimiter.tryLogin(address(i)).allowed());
        assertEquals(1024, mapLimiter.addressCount());
        now.set(1800 * SECOND - 1);
        assertEquals(1, mapLimiter.tryLogin(address(1024)).retryAfterSeconds());
        assertEquals(1024, mapLimiter.addressCount());
        now.incrementAndGet();
        assertTrue(mapLimiter.tryLogin(address(1024)).allowed());
        assertEquals(1, mapLimiter.addressCount());
    }

    @Test void existingMapEntryRemainsUsableWhenMapIsFull() {
        var limits = new DemoAbuseLimiter.Limits(10, 1800 * SECOND, 5, 60 * SECOND,
            30, 3600 * SECOND, 1, 60, 60 * SECOND);
        var small = new DemoAbuseLimiter(now::get, limits);
        assertTrue(small.tryLogin(address(1)).allowed());
        assertFalse(small.tryLogin(address(2)).allowed());
        assertTrue(small.tryLogin(address(1)).allowed());
        assertEquals(1, small.addressCount());
    }

    @Test void ipv6PrefixAndMappedIpv4UseSameBuckets() {
        var limits = new DemoAbuseLimiter.Limits(1, 1800 * SECOND, 5, 60 * SECOND,
            30, 3600 * SECOND, 1024, 60, 60 * SECOND);
        var one = new DemoAbuseLimiter(now::get, limits);
        assertTrue(one.tryLogin(DemoClientAddress.parse("2001:db8:1:2::1")).allowed());
        assertFalse(one.tryLogin(DemoClientAddress.parse("2001:0db8:0001:0002:ffff::2")).allowed());
        assertTrue(one.tryLogin(DemoClientAddress.parse("192.0.2.1")).allowed());
        assertFalse(one.tryLogin(DemoClientAddress.parse("::ffff:192.0.2.1")).allowed());
    }
}
