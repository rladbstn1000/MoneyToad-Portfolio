package com.potg.don.auth.demo;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/** One JVM only. Initial portfolio limits are operational assumptions, not measured capacity. */
public final class DemoAbuseLimiter {
    private static final Limits DEFAULTS = new Limits(10, Duration.ofMinutes(30).toNanos(),
        5, Duration.ofMinutes(1).toNanos(), 30, Duration.ofHours(1).toNanos(), 1024,
        60, Duration.ofMinutes(1).toNanos());
    private final LongSupplier nanoTime;
    private final Limits limits;
    // In last-accepted order, never updated by a rejected request.
    private final LinkedHashMap<DemoClientAddress, IpEntry> addresses = new LinkedHashMap<>();
    private final Window shortLogin;
    private final Window longLogin;
    private final Window readiness;

    public DemoAbuseLimiter() { this(System::nanoTime); }
    public DemoAbuseLimiter(LongSupplier nanoTime) { this(nanoTime, DEFAULTS); }
    DemoAbuseLimiter(LongSupplier nanoTime, Limits limits) {
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
        this.limits = limits;
        shortLogin = new Window(limits.shortLimit(), limits.shortWindowNanos());
        longLogin = new Window(limits.longLimit(), limits.longWindowNanos());
        readiness = new Window(limits.readyLimit(), limits.readyWindowNanos());
    }

    public synchronized Decision tryLogin(DemoClientAddress address) {
        java.util.Objects.requireNonNull(address);
        long now = nanoTime.getAsLong();
        shortLogin.prune(now);
        longLogin.prune(now);
        // At most maxIpEntries removals; no background scheduler or request-created thread.
        var entries = addresses.entrySet().iterator();
        while (entries.hasNext()) {
            IpEntry entry = entries.next().getValue();
            if (now - entry.lastAccepted < limits.ipWindowNanos()) break;
            entries.remove();
        }
        IpEntry entry = addresses.get(address);
        long wait = Math.max(shortLogin.waitNanos(now), longLogin.waitNanos(now));
        if (entry != null) {
            entry.window.prune(now);
            wait = Math.max(wait, entry.window.waitNanos(now));
        } else if (addresses.size() >= limits.maxIpEntries()) {
            IpEntry oldest = addresses.firstEntry().getValue();
            wait = Math.max(wait, limits.ipWindowNanos() - (now - oldest.lastAccepted));
        }
        if (wait > 0) return rejected(wait);
        if (entry == null) entry = new IpEntry(new Window(limits.ipLimit(), limits.ipWindowNanos()));
        entry.window.append(now);
        entry.lastAccepted = now;
        addresses.remove(address);
        addresses.put(address, entry);
        shortLogin.append(now);
        longLogin.append(now);
        return Decision.ALLOWED;
    }

    public synchronized Decision tryReadiness() {
        long now = nanoTime.getAsLong();
        readiness.prune(now);
        long wait = readiness.waitNanos(now);
        if (wait > 0) return rejected(wait);
        readiness.append(now);
        return Decision.ALLOWED;
    }

    private static Decision rejected(long nanos) {
        long seconds = nanos / 1_000_000_000L + (nanos % 1_000_000_000L == 0 ? 0 : 1);
        return new Decision(false, Math.max(1, seconds));
    }

    // Package-only observable size for deterministic bound tests, never an HTTP metric or key dump.
    synchronized int addressCount() { return addresses.size(); }

    public record Decision(boolean allowed, long retryAfterSeconds) {
        private static final Decision ALLOWED = new Decision(true, 0);
    }

    record Limits(int ipLimit, long ipWindowNanos, int shortLimit, long shortWindowNanos,
        int longLimit, long longWindowNanos, int maxIpEntries, int readyLimit, long readyWindowNanos) {
        Limits {
            if (ipLimit < 1 || shortLimit < 1 || longLimit < 1 || maxIpEntries < 1 || maxIpEntries > 1024
                || readyLimit < 1 || ipWindowNanos < 1 || shortWindowNanos < 1
                || longWindowNanos < 1 || readyWindowNanos < 1) throw new IllegalArgumentException("INVALID_ABUSE_LIMITS");
        }
    }

    private static final class IpEntry {
        private final Window window;
        private long lastAccepted;
        private IpEntry(Window window) { this.window = window; }
    }

    private static final class Window {
        private final long[] timestamps;
        private final long duration;
        private int head;
        private int size;
        private Window(int capacity, long duration) { timestamps = new long[capacity]; this.duration = duration; }
        private void prune(long now) {
            while (size > 0 && now - timestamps[head] >= duration) {
                head = (head + 1) % timestamps.length;
                size--;
            }
        }
        private long waitNanos(long now) {
            return size < timestamps.length ? 0 : duration - (now - timestamps[head]);
        }
        private void append(long now) {
            if (size >= timestamps.length) throw new IllegalStateException("ABUSE_WINDOW_OVERFLOW");
            timestamps[(head + size) % timestamps.length] = now;
            size++;
        }
    }
}
