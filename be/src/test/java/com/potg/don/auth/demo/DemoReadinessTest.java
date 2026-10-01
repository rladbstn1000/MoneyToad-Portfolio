package com.potg.don.auth.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DemoReadinessTest {
    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private ResultSet result;
    private RedisConnectionFactory redis;
    private RedisConnection redisConnection;
    private ExecutorService worker;
    private DemoReadinessService service;

    @BeforeEach void prepare() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        result = mock(ResultSet.class);
        redis = mock(RedisConnectionFactory.class);
        redisConnection = mock(RedisConnection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1);
        when(redis.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        service = new DemoReadinessService(dataSource, redis, worker, 1000);
    }

    @AfterEach void stopOnlyTestWorker() throws Exception {
        Thread.interrupted();
        service.close();
        assertThat(worker.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test void checksOnlyBoundedSelectAndPingAndClosesResources() throws Exception {
        assertThat(service.ready()).isTrue();
        verify(statement).setQueryTimeout(1);
        verify(statement).executeQuery("SELECT 1");
        verify(redisConnection).ping();
        verify(result).close();
        verify(statement).close();
        verify(connection).close();
        verify(redisConnection).close();
        verifyNoMoreInteractions(statement, redisConnection);
    }

    @Test void databaseFailureDoesNotProbeRedisOrReturnReady() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("PRIVATE_TEST_FAILURE"));
        assertThat(service.ready()).isFalse();
        verifyNoInteractions(redis, redisConnection);
    }

    @Test void unexpectedSelectValueIsNotReadiness() throws Exception {
        when(result.getInt(1)).thenReturn(0);
        assertThat(service.ready()).isFalse();
        verifyNoInteractions(redis, redisConnection);
    }

    @Test void missingSelectRowIsNotReadiness() throws Exception {
        when(result.next()).thenReturn(false);
        assertThat(service.ready()).isFalse();
        verifyNoInteractions(redis, redisConnection);
    }

    @Test void nonPongRedisResponseIsNotReadiness() {
        when(redisConnection.ping()).thenReturn(null);
        assertThat(service.ready()).isFalse();
    }

    @Test void redisFailureReturnsFalseWithoutExposingItsText() {
        when(redisConnection.ping()).thenThrow(new IllegalStateException("PRIVATE_TEST_FAILURE"));
        assertThat(service.ready()).isFalse();
        verify(redisConnection).close();
    }

    @Test void deadlineCancelsAnUnreadyProbeWithoutAnUnboundedQueue() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        when(dataSource.getConnection()).thenAnswer(call -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException failure) { interrupted.countDown(); throw new SQLException("INTERRUPTED"); }
            return connection;
        });
        service = new DemoReadinessService(dataSource, redis, worker, 50);
        assertThat(service.ready()).isFalse();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        verifyNoInteractions(redis, redisConnection);
    }

    @Test void busyProbeRejectsAnotherCheckWithoutQueueingOrMoreDatabaseWork() throws Exception {
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        worker.submit(() -> { occupied.countDown(); try { release.await(); } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } });
        assertThat(occupied.await(1, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(service.ready()).isFalse();
            verifyNoInteractions(dataSource, redis);
        } finally { release.countDown(); }
    }

    @Test void interruptedCallerKeepsInterruptAndFailsClosed() {
        Thread.currentThread().interrupt();
        assertThat(service.ready()).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(dataSource, redis);
    }

    @Test void successfulHttpContractHasOnlyReadyBooleanAndNoStore() throws Exception {
        var probe = mock(DemoReadinessService.class);
        when(probe.ready()).thenReturn(true);
        var response = MockMvcBuilders.standaloneSetup(new DemoReadinessController(probe)).build()
            .perform(get("/auth/demo/ready")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"ready\":true}");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test void unavailableHttpContractIs503NotAuthenticationFailure() throws Exception {
        var probe = mock(DemoReadinessService.class);
        var response = MockMvcBuilders.standaloneSetup(new DemoReadinessController(probe)).build()
            .perform(get("/auth/demo/ready")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEqualTo("{\"ready\":false}");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test void postDoesNotInvokeReadiness() throws Exception {
        var probe = mock(DemoReadinessService.class);
        var response = MockMvcBuilders.standaloneSetup(new DemoReadinessController(probe)).build()
            .perform(post("/auth/demo/ready")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(405);
        verifyNoInteractions(probe);
    }
}
