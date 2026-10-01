package com.potg.verification.managed;

import static com.potg.verification.managed.ManagedSqlContracts.require;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BiPredicate;

import org.springframework.core.io.ClassPathResource;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.RedisCommand;
import io.netty.buffer.Unpooled;

/** Application-command boundary only; native handshake has its separate finite allowance. */
final class ManagedRedisCommandGate {
    private final BiPredicate<CommandType,String> permittedKey;
    private final List<Script> scripts;

    ManagedRedisCommandGate(BiPredicate<CommandType,String> permittedKey) throws Exception {
        this.permittedKey = permittedKey;
        scripts = List.of(script("demo-session-create.lua", 6),
            script("demo-session-find-active.lua", 3), script("demo-session-rotate.lua", 7));
    }

    static ClientOptions noReplayOptions() {
        return ClientOptions.builder().autoReconnect(false)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .timeoutOptions(TimeoutOptions.enabled())
            .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(3)).build()).build();
    }

    void check(RedisCommand<?,?,?> command) {
        require(command.getType() instanceof CommandType, "REDIS_COMMAND_ALLOWLIST");
        CommandType type = (CommandType) command.getType();
        CommandArgs<?,?> args = command.getArgs();
        if (type == CommandType.PING) {
            require(args == null || args.count() == 0, "REDIS_ARGUMENT_BOUNDARY");
            return;
        }
        require(args != null, "REDIS_ARGUMENT_BOUNDARY");
        switch (type) {
            case EXISTS, DEL, HGETALL, PTTL -> require(args.count() == 1, "REDIS_ARGUMENT_BOUNDARY");
            case PEXPIRE -> require(args.count() == 2 && Long.valueOf(1500).equals(args.getFirstInteger()), "REDIS_ARGUMENT_BOUNDARY");
            case EVAL, EVALSHA -> {
                require(Long.valueOf(1).equals(args.getFirstInteger()) && args.count() >= 3 && args.count() <= 7,
                    "REDIS_SCRIPT_KEY_BOUNDARY");
                byte[] first = firstArgument(args);
                require(scripts.stream().anyMatch(script -> script.arguments == args.count()
                    && Arrays.equals(type == CommandType.EVAL ? script.body : script.sha, first)),
                    "PRODUCT_LUA_ONLY");
            }
            default -> throw new ManagedSqlContracts.ContractFailure("REDIS_COMMAND_ALLOWLIST");
        }
        var key = args.getFirstEncodedKey();
        require(key != null, "REDIS_KEY_REQUIRED");
        String value = StandardCharsets.UTF_8.decode(key.asReadOnlyBuffer()).toString();
        require(value.matches("demo:session:[A-Za-z0-9_-]{43}") && permittedKey.test(type,value),
            "UNOWNED_REDIS_ACCESS_REFUSED");
    }

    private static Script script(String name, int arguments) throws Exception {
        byte[] body = new ClassPathResource("redis/" + name).getContentAsByteArray();
        byte[] sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(body))
            .getBytes(StandardCharsets.US_ASCII);
        return new Script(body,sha,arguments);
    }

    private record Script(byte[] body, byte[] sha, int arguments) { }

    /** Decode only the first bounded RESP bulk argument; never render or retain other arguments. */
    private static byte[] firstArgument(CommandArgs<?,?> args) {
        var buffer = Unpooled.buffer(256,32768);
        try {
            args.encode(buffer);
            require(buffer.isReadable() && buffer.readByte() == '$', "REDIS_ARGUMENT_BOUNDARY");
            int length = 0, digits = 0;
            while (buffer.isReadable() && buffer.getByte(buffer.readerIndex()) != '\r') {
                int digit = buffer.readByte() - '0';
                require(digit >= 0 && digit <= 9 && ++digits <= 5, "REDIS_ARGUMENT_BOUNDARY");
                length = length * 10 + digit;
            }
            require(digits > 0 && length <= 16384 && buffer.readableBytes() >= length + 4
                && buffer.readByte() == '\r' && buffer.readByte() == '\n', "REDIS_ARGUMENT_BOUNDARY");
            byte[] first = new byte[length]; buffer.readBytes(first);
            require(buffer.readByte() == '\r' && buffer.readByte() == '\n', "REDIS_ARGUMENT_BOUNDARY");
            return first;
        } catch (RuntimeException failure) {
            throw new ManagedSqlContracts.ContractFailure("REDIS_ARGUMENT_BOUNDARY");
        } finally {
            buffer.setZero(0,buffer.writerIndex()); buffer.release();
        }
    }
}
