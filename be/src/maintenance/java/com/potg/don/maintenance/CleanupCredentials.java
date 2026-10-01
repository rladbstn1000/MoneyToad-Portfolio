package com.potg.don.maintenance;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Deliberately separate from runtime/provider credentials. Does not evaluate shell input. */
public record CleanupCredentials(String url, String username, String password, int expectedMax) {
    private static final Set<String> KEYS = Set.of("DB_URL", "DB_USERNAME", "DB_PASSWORD", "DEMO_MAX_VISITORS");
    private static final Set<String> URL_OPTIONS = Set.of("sslMode", "connectTimeout", "socketTimeout",
        "connectionTimeZone", "forceConnectionTimeZoneToSession", "enabledTLSProtocols", "readOnlyPropagatesToServer");
    public static CleanupCredentials read(Path file, String expectedSchema) {
        try {
            Path normalized = file.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            var owner = Files.getOwner(Path.of(System.getProperty("user.home")), LinkOption.NOFOLLOW_LINKS);
            for (Path item = normalized; item != null; item = item.getParent()) {
                if (Files.isSymbolicLink(item)) throw rejected();
                if (Files.exists(item.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) throw rejected();
            }
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS).equals(owner)
                || !Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))) throw rejected();
            var before = attributes(normalized, owner);
            if (before.size() < 1 || before.size() > 16384) throw rejected();
            ByteBuffer bytes = ByteBuffer.allocate(16385);
            try (SeekableByteChannel channel = Files.newByteChannel(normalized, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
            }
            var after = attributes(normalized, owner);
            if (!before.fileKey().equals(after.fileKey()) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime()) || bytes.position() != before.size()) throw rejected();
            bytes.flip();
            String input = StandardCharsets.UTF_8.newDecoder().decode(bytes).toString();
            Map<String, String> values = new HashMap<>();
            for (String line : input.split("\\r?\\n", -1)) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int separator = line.indexOf('=');
                if (separator < 1) throw rejected();
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (!KEYS.contains(key) || value.isEmpty() || value.chars().anyMatch(c -> c < 32 || c == 127)
                    || values.putIfAbsent(key, value) != null) throw rejected();
            }
            if (!values.keySet().equals(KEYS)) throw rejected();
            String url = values.get("DB_URL");
            if (!url.startsWith("jdbc:mysql://")) throw rejected();
            URI target = URI.create(url.substring(5));
            if (target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null
                || !target.getPath().equals("/" + expectedSchema) || target.getRawQuery() == null) throw rejected();
            Map<String, String> options = new HashMap<>();
            for (String item : target.getRawQuery().split("&", -1)) {
                String[] pair = item.split("=", -1);
                if (pair.length != 2 || !URL_OPTIONS.contains(pair[0]) || options.putIfAbsent(pair[0], pair[1]) != null) throw rejected();
            }
            if (!"VERIFY_IDENTITY".equals(options.get("sslMode"))
                || !"false".equals(options.get("readOnlyPropagatesToServer"))) throw rejected();
            boundedTimeout(options, "connectTimeout", 5000);
            boundedTimeout(options, "socketTimeout", 10000);
            int maximum = Integer.parseInt(values.get("DEMO_MAX_VISITORS"));
            if (maximum < 1) throw rejected();
            return new CleanupCredentials(url, values.get("DB_USERNAME"), values.get("DB_PASSWORD"), maximum);
        } catch (IOException | RuntimeException error) { throw rejected(); }
    }
    private static BasicFileAttributes attributes(Path file, java.nio.file.attribute.UserPrincipal owner) throws IOException {
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.fileKey() == null
            || !Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).equals(owner)
            || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))
            || ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) throw rejected();
        return attributes;
    }
    private static void boundedTimeout(Map<String, String> values, String key, int ceiling) {
        int timeout = Integer.parseInt(values.getOrDefault(key, "0"));
        if (timeout < 1 || timeout > ceiling) throw rejected();
    }
    @Override public String toString() { return "CleanupCredentials[redacted]"; }
    private static CleanupFailure rejected() { return new CleanupFailure(CleanupFailure.Code.CREDENTIAL_FILE_REJECTED); }
}
