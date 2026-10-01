package com.potg.don.auth.demo;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/** A bounded, read-only dependency probe. It never creates a user or session. */
@Service
@Profile("demo")
public class DemoReadinessService {
    private final DataSource dataSource;
    private final RedisConnectionFactory redis;
    private final ExecutorService worker;
    private final long timeoutMillis;

    @Autowired
    public DemoReadinessService(DataSource dataSource, RedisConnectionFactory redis) {
        this(dataSource, redis, new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "demo-readiness");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy()), 8_000);
    }

    DemoReadinessService(DataSource dataSource, RedisConnectionFactory redis,
        ExecutorService worker, long timeoutMillis) {
        this.dataSource = dataSource;
        this.redis = redis;
        this.worker = worker;
        this.timeoutMillis = timeoutMillis;
    }

    public boolean ready() {
        if (Thread.currentThread().isInterrupted()) return false;
        Future<Boolean> check;
        try { check = worker.submit(this::dependenciesReady); }
        catch (RejectedExecutionException busy) { return false; }
        try { return check.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            check.cancel(true);
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException unavailable) {
            check.cancel(true);
            return false;
        } catch (ExecutionException unavailable) {
            // A readiness result never serializes a driver exception or credential.
            return false;
        }
    }

    private boolean dependenciesReady() throws Exception {
        // Close JDBC resources before waiting for Redis. No repository/seed work.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            try (ResultSet result = statement.executeQuery("SELECT 1")) {
                if (!result.next() || result.getInt(1) != 1) return false;
            }
        }
        try (RedisConnection connection = redis.getConnection()) {
            return "PONG".equals(connection.ping());
        }
    }

    @PreDestroy
    public void close() { worker.shutdownNow(); }
}
