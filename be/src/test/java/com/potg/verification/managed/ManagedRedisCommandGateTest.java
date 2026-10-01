package com.potg.verification.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.potg.don.auth.demo.DemoSessionStore;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.CommandListenerWriter;
import io.lettuce.core.RedisChannelWriter;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.event.command.CommandFailedEvent;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.RedisCommand;
import io.lettuce.core.resource.DefaultClientResources;

/** Synthetic commands and one owned loopback serializer fixture; no provider access. */
class ManagedRedisCommandGateTest {
    private static final String SID = "A".repeat(43);
    private static final String KEY = "demo:session:" + SID;
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static ManagedRedisCommandGate gate() throws Exception {
        return new ManagedRedisCommandGate((type,key)->KEY.equals(key));
    }
    private static RedisCommand<byte[],byte[],Object> command(CommandType type, CommandArgs<byte[],byte[]> args) {
        return new Command<>(type,null,args);
    }
    private static CommandArgs<byte[],byte[]> args() { return new CommandArgs<>(ByteArrayCodec.INSTANCE); }
    private static CommandArgs<byte[],byte[]> scriptArgs(String file, boolean sha, int values) throws Exception {
        byte[] script = new ClassPathResource("redis/" + file).getContentAsByteArray();
        var args = args();
        if (sha) args.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(script)));
        else args.add(script);
        args.add(1).addKey(bytes(KEY));
        for (int i=0;i<values;i++) args.addValue(bytes("synthetic-value"));
        return args;
    }

    @Test void disablesReplayAndPreservesThreeSecondConnectAndCommandTimeouts() {
        var options = ManagedRedisCommandGate.noReplayOptions();
        assertThat(options.isAutoReconnect()).isFalse();
        assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(options.getTimeoutOptions().isTimeoutCommands()).isTrue();
    }

    @Test void acceptsOnlyExactProductScriptsAndTheirSingleKeyArgumentShapes() throws Exception {
        var gate = gate();
        for (boolean sha : List.of(false,true)) {
            gate.check(command(sha?CommandType.EVALSHA:CommandType.EVAL,scriptArgs("demo-session-create.lua",sha,3)));
            gate.check(command(sha?CommandType.EVALSHA:CommandType.EVAL,scriptArgs("demo-session-find-active.lua",sha,0)));
            gate.check(command(sha?CommandType.EVALSHA:CommandType.EVAL,scriptArgs("demo-session-rotate.lua",sha,4)));
        }
        assertThatThrownBy(()->gate.check(command(CommandType.EVAL,args().add(bytes("return redis.call('FLUSHALL')")).add(1).addKey(bytes(KEY)))))
            .hasMessage("PRODUCT_LUA_ONLY");
        assertThatThrownBy(()->gate.check(command(CommandType.EVAL,scriptArgs("demo-session-create.lua",false,0))))
            .hasMessage("PRODUCT_LUA_ONLY");
        assertThatThrownBy(()->gate.check(command(CommandType.EVAL,args().add(bytes("return 1")).add(2).addKey(bytes(KEY)).addKey(bytes(KEY)))))
            .hasMessage("REDIS_SCRIPT_KEY_BOUNDARY");
    }

    @Test void refusesUnownedKeysMultiKeyCallsAndGlobalCommandsWithoutLeakingValues() throws Exception {
        var gate=gate(); String other="demo:session:"+"B".repeat(43);
        for (CommandType type : List.of(CommandType.DEL,CommandType.EXISTS,CommandType.PTTL,CommandType.HGETALL)) {
            gate.check(command(type,args().addKey(bytes(KEY))));
            assertThatThrownBy(()->gate.check(command(type,args().addKey(bytes(other)))))
                .hasMessage("UNOWNED_REDIS_ACCESS_REFUSED");
            assertThatThrownBy(()->gate.check(command(type,args().addKey(bytes(KEY)).addKey(bytes(other)))))
                .hasMessage("REDIS_ARGUMENT_BOUNDARY");
        }
        for (CommandType type : List.of(CommandType.KEYS,CommandType.SCAN,CommandType.FLUSHDB,CommandType.FLUSHALL,CommandType.SCRIPT))
            assertThatThrownBy(()->gate.check(command(type,args().add("synthetic-secret-canary"))))
                .hasMessage("REDIS_COMMAND_ALLOWLIST");
        gate.check(command(CommandType.PEXPIRE,args().addKey(bytes(KEY)).add(1500)));
        assertThatThrownBy(()->gate.check(command(CommandType.PEXPIRE,args().addKey(bytes(KEY)).add(2000))))
            .hasMessage("REDIS_ARGUMENT_BOUNDARY");
    }

    @Test void existenceCandidateExceptionIsOnlyAnExistsRead() throws Exception {
        var gate=new ManagedRedisCommandGate((type,key)->type==CommandType.EXISTS && KEY.equals(key));
        gate.check(command(CommandType.EXISTS,args().addKey(bytes(KEY))));
        assertThatThrownBy(()->gate.check(command(CommandType.DEL,args().addKey(bytes(KEY)))))
            .hasMessage("UNOWNED_REDIS_ACCESS_REFUSED");
    }

    @Test void installedListenerWriterRejectsBeforeSingleOrBatchDelegateDispatch() throws Exception {
        var gate=gate(); AtomicInteger dispatched=new AtomicInteger();
        RedisChannelWriter delegate=(RedisChannelWriter)Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{RedisChannelWriter.class},(proxy,method,values)->{
                if (method.getName().equals("write")) {dispatched.incrementAndGet();return values[0];}
                return null;
            });
        var writer=new CommandListenerWriter(delegate,List.of(new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) {gate.check(event.getCommand());}
        }));
        var denied=command(CommandType.FLUSHALL,args());
        var allowed=command(CommandType.EXISTS,args().addKey(bytes(KEY)));
        assertThatThrownBy(()->writer.write(denied)).hasMessage("REDIS_COMMAND_ALLOWLIST");
        assertThatThrownBy(()->writer.write(List.of(allowed,denied))).hasMessage("REDIS_COMMAND_ALLOWLIST");
        assertThat(dispatched.get()).isZero();
        writer.write(allowed); assertThat(dispatched.get()).isEqualTo(1);
    }

    @Test void actualStringRedisTemplateSerializationAndNoscriptFallbackPassGateOnOwnedLoopback() throws Exception {
        var gate=gate(); AtomicInteger noScripts=new AtomicInteger();
        var resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        LettuceConnectionFactory factory=null;
        try (var fixture=new RespFixture()) {
            var standalone=new RedisStandaloneConfiguration("127.0.0.1",fixture.port());
            var client=LettuceClientConfiguration.builder().clientResources(resources)
                .clientOptions(ManagedRedisCommandGate.noReplayOptions()).commandTimeout(Duration.ofSeconds(2)).build();
            factory=new LettuceConnectionFactory(standalone,client); factory.afterPropertiesSet();factory.start();
            factory.getNativeClient().addListener(new CommandListener() {
                @Override public void commandStarted(CommandStartedEvent event) {gate.check(event.getCommand());}
                @Override public void commandFailed(CommandFailedEvent event) {
                    if(event.getCommand().getType()==CommandType.EVALSHA && event.getCause() instanceof RedisNoScriptException)
                        noScripts.incrementAndGet();
                }
            });
            var store=new DemoSessionStore(new StringRedisTemplate(factory));
            var deadline=Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            assertThat(store.create(SID,1,"1".repeat(64),deadline)).isTrue();
            assertThat(store.findActive(SID)).contains(new DemoSessionStore.SessionIdentity(1,deadline));
            assertThat(store.rotate(SID,1,deadline,"1".repeat(64),"2".repeat(64))).isEqualTo(DemoSessionStore.RotationResult.ROTATED);
            store.revoke(SID);
            assertThat(fixture.commands.stream().filter("EVALSHA"::equals).count()).isEqualTo(3);
            assertThat(fixture.commands.stream().filter("EVAL"::equals).count()).isEqualTo(3);
            assertThat(noScripts.get()).isEqualTo(3);
            factory.destroy();factory=null;
        } finally {
            if(factory!=null)factory.destroy();
            assertThat(resources.shutdown(0,2,TimeUnit.SECONDS).get(3,TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Protocol fixture only: proves serialization/dispatch, not Redis Lua semantics. */
    private static final class RespFixture implements AutoCloseable {
        final ServerSocket server=new ServerSocket();
        final AtomicReference<Socket> accepted=new AtomicReference<>();
        final List<String> commands=new CopyOnWriteArrayList<>();
        final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
        final java.util.concurrent.Future<?> completion;
        RespFixture() throws Exception {
            server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),0));
            completion=worker.submit(()->{
                try (Socket socket=server.accept()) {
                    accepted.set(socket);socket.setSoTimeout(5000);
                    var input=new BufferedInputStream(socket.getInputStream());var output=socket.getOutputStream();
                    String deadline="0";
                    while(!socket.isClosed()) {
                        var values=readCommand(input); String name=values.getFirst();commands.add(name);
                        String reply=switch(name) {
                            case "HELLO" -> "%7\r\n+server\r\n+redis\r\n+version\r\n+7.4.0\r\n+proto\r\n:3\r\n+id\r\n:1\r\n+mode\r\n+standalone\r\n+role\r\n+master\r\n+modules\r\n*0\r\n";
                            case "CLIENT" -> "+OK\r\n";
                            case "PING" -> "+PONG\r\n";
                            case "EVALSHA" -> "-NOSCRIPT synthetic cache miss\r\n";
                            case "EVAL" -> {
                                if(values.size()==7)deadline=values.get(6);
                                yield values.size()==4?"*2\r\n$1\r\n1\r\n$"+deadline.length()+"\r\n"+deadline+"\r\n":":1\r\n";
                            }
                            case "DEL" -> ":1\r\n";
                            default -> "-ERR synthetic unexpected command\r\n";
                        };
                        output.write(bytes(reply));output.flush();
                    }
                } catch (EOFException expectedClose) { }
                catch (Exception failure) {if(!server.isClosed())throw new IllegalStateException("LOOPBACK_FIXTURE_FAILED");}
            });
        }
        int port(){return server.getLocalPort();}
        static List<String> readCommand(InputStream input) throws Exception {
            String first=line(input);if(!first.startsWith("*"))throw new IllegalStateException("RESP_ARRAY_REQUIRED");
            int count=Integer.parseInt(first.substring(1));if(count<1||count>16)throw new IllegalStateException("RESP_LIMIT");
            var values=new ArrayList<String>();
            for(int i=0;i<count;i++) {
                String header=line(input);if(!header.startsWith("$"))throw new IllegalStateException("RESP_BULK_REQUIRED");
                int length=Integer.parseInt(header.substring(1));if(length<0||length>16384)throw new IllegalStateException("RESP_LIMIT");
                byte[] value=input.readNBytes(length);
                if(value.length!=length||input.read()!='\r'||input.read()!='\n')throw new EOFException();
                values.add(new String(value,StandardCharsets.UTF_8));
            }
            return values;
        }
        static String line(InputStream input) throws Exception {
            var value=new StringBuilder();
            for(int next;(next=input.read())!=-1;) {
                if(next=='\r'){if(input.read()!='\n')throw new EOFException();return value.toString();}
                if(value.length()>64)throw new IllegalStateException("RESP_LINE_LIMIT");value.append((char)next);
            }
            throw new EOFException();
        }
        @Override public void close() throws Exception {
            server.close();Socket socket=accepted.get();if(socket!=null)socket.close();
            worker.shutdownNow();assertThat(worker.awaitTermination(3,TimeUnit.SECONDS)).isTrue();
            completion.get(3,TimeUnit.SECONDS);
        }
    }
}
