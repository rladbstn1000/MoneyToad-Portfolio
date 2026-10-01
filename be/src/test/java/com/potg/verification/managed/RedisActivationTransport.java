package com.potg.verification.managed;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.CompleteableCommand;
import io.lettuce.core.protocol.RedisCommand;
import io.lettuce.core.resource.NettyCustomizer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

/** Observes command metadata, never arguments, results, addresses or wire bytes. */
final class RedisActivationTransport implements NettyCustomizer {
    private static final Set<String> ALLOWED = Set.of("HELLO", "AUTH", "PING", "CLIENT", "SELECT", "QUIT");
    private final RedisActivationObservation observation;
    private final AtomicInteger commands = new AtomicInteger();
    private final AtomicInteger connections = new AtomicInteger();

    RedisActivationTransport(RedisActivationObservation observation) { this.observation = observation; }
    int commandCount() { return commands.get(); }
    int connectionCount() { return connections.get(); }

    static String commandName(RedisCommand<?, ?, ?> command) {
        return command.getType() instanceof CommandType type && ALLOWED.contains(type.name())
            ? type.name() : "UNEXPECTED_COMMAND";
    }

    @Override public void afterChannelInitialized(Channel channel) {
        channel.pipeline().addLast("safe-activation-observation", new ChannelDuplexHandler() {
            @Override public void connect(ChannelHandlerContext context, SocketAddress remote, SocketAddress local,
                                          ChannelPromise promise) throws Exception {
                if (connections.incrementAndGet() > 1) {
                    var failure = new ManagedSqlContracts.ContractFailure("DIAGNOSTIC_CONNECTION_LIMIT");
                    observation.failure(failure); promise.setFailure(failure); context.close(); return;
                }
                if (remote instanceof InetSocketAddress address && !address.isUnresolved())
                    observation.stage("DNS", "PASS");
                observation.stage("TCP", "STARTED");
                promise.addListener(future -> {
                    observation.stage("TCP", future.isSuccess() ? "PASS" : "FAIL");
                    if (!future.isSuccess() && future.cause() != null) observation.failure(future.cause());
                });
                context.connect(remote, local, promise);
            }

            @Override public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
                if (event instanceof SslHandshakeCompletionEvent tls) {
                    observation.stage("TLS", tls.isSuccess() ? "PASS" : "FAIL");
                    if (tls.isSuccess()) observation.stage("HANDSHAKE", "STARTED");
                    else observation.failure(tls.cause());
                }
                context.fireUserEventTriggered(event);
            }

            @Override public void exceptionCaught(ChannelHandlerContext context, Throwable failure) throws Exception {
                observation.failure(failure);
                context.fireExceptionCaught(failure);
            }

            @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                if (message instanceof RedisCommand<?, ?, ?> command) {
                    if (!observe(command)) { reject(context, command, promise); return; }
                } else if (message instanceof Collection<?> batch) {
                    for (Object item : batch) {
                        if (item instanceof RedisCommand<?, ?, ?> command && !observe(command)) {
                            for (Object pending : batch) if (pending instanceof RedisCommand<?, ?, ?> rejected)
                                rejected.completeExceptionally(new ManagedSqlContracts.ContractFailure("DIAGNOSTIC_COMMAND_LIMIT_OR_KIND"));
                            reject(context, command, promise); return;
                        }
                    }
                }
                context.write(message, promise);
            }

            private void reject(ChannelHandlerContext context, RedisCommand<?, ?, ?> command, ChannelPromise promise) {
                var failure = new ManagedSqlContracts.ContractFailure("DIAGNOSTIC_COMMAND_LIMIT_OR_KIND");
                observation.failure(failure); command.completeExceptionally(failure);
                promise.tryFailure(failure); context.close();
            }
        });
    }

    private boolean observe(RedisCommand<?, ?, ?> command) {
        String name = commandName(command);
        observation.command(name, "STARTED");
        if (commands.incrementAndGet() > 64 || name.equals("UNEXPECTED_COMMAND")) return false;
        if (command instanceof CompleteableCommand<?> completeable) {
            completeable.onComplete((ignored, failure) -> {
                observation.command(name, failure == null ? "SUCCEEDED" : "FAILED");
                if (failure != null) observation.failure(failure);
            });
        }
        return true;
    }
}
