package com.potg.don.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

class DemoCleanupInputTest {
    private static final String SYNTHETIC_CREDENTIAL = UUID.randomUUID().toString();
    @TempDir Path temporary;

    @Test void defaultsAndExplicitModesHaveBoundedBatchAndNoDeleteByIdentityOption() {
        var dry = CleanupOptions.parse(new String[] {"--config-file", "/nonexistent/private/config", "--schema", "synthetic_check"});
        assertThat(dry.mode()).isEqualTo(CleanupOptions.Mode.DRY_RUN);
        assertThat(dry.batchSize()).isEqualTo(10);
        var apply = CleanupOptions.parse(new String[] {"--apply", "--config-file", "/nonexistent/private/config", "--schema", "synthetic_check", "--batch-size", "100"});
        assertThat(apply.mode()).isEqualTo(CleanupOptions.Mode.APPLY);
        assertThat(apply.batchSize()).isEqualTo(100);
    }

    static Stream<Arguments> invalidArguments() {
        return Stream.of(new String[] {"--force"}, new String[] {"--apply", "--verify"},
            new String[] {"--batch-size", "0"}, new String[] {"--batch-size", "101"},
            new String[] {"--batch-size", "-1"}, new String[] {"--batch-size", "1", "--batch-size", "2"},
            new String[] {"--user-id", "1"}, new String[] {"--email", "placeholder"},
            new String[] {"--hours", "1"}, new String[] {"--config-file"}, new String[] {"--password", "hidden"}).map(arguments -> Arguments.of((Object) arguments));
    }
    @ParameterizedTest @MethodSource("invalidArguments")
    void rejectsUnsupportedOrOutOfRangeInputs(String[] extra) {
        String[] args = new String[extra.length + 4];
        String[] base = {"--config-file", "/nonexistent/private/config", "--schema", "synthetic_check"};
        System.arraycopy(base, 0, args, 0, 4); System.arraycopy(extra, 0, args, 4, extra.length);
        assertThatThrownBy(() -> CleanupOptions.parse(args)).isInstanceOf(CleanupFailure.class)
            .hasMessage("INPUT_REJECTED");
    }

    @Test void privateFileIsReadWithoutExposingValuesAndRequiresExactSchemaAndTls() throws Exception {
        Path path = file();
        var result = CleanupCredentials.read(path, "synthetic_check");
        assertThat(result.expectedMax()).isEqualTo(1000);
        assertThat(result.url()).contains("readOnlyPropagatesToServer=false");
        assertThat(result.toString()).isEqualTo("CleanupCredentials[redacted]");
        assertThatThrownBy(() -> CleanupCredentials.read(path, "other_check")).hasMessage("CREDENTIAL_FILE_REJECTED");
        Files.writeString(path, content().replace("VERIFY_IDENTITY", "REQUIRED"));
        assertThatThrownBy(() -> CleanupCredentials.read(path, "synthetic_check")).hasMessage("CREDENTIAL_FILE_REJECTED");
    }

    @Test void permissionsSymlinkHardlinkAndGitDirectoryAreRejected() throws Exception {
        Path path = file();
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        assertRejected(path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        Path link = path.resolveSibling("linked");
        Files.createSymbolicLink(link, path); assertRejected(link); Files.delete(link);
        Files.createLink(link, path); assertRejected(path); Files.delete(link);
        Files.setPosixFilePermissions(path.getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
        assertRejected(path);
        Files.setPosixFilePermissions(path.getParent(), PosixFilePermissions.fromString("rwx------"));
        Files.createDirectory(path.getParent().resolve(".git")); assertRejected(path);
    }

    @Test void unknownDuplicateAndUnboundedDriverOptionsAreRejected() throws Exception {
        Path path = file();
        for (String invalid : new String[] {content() + "DB_PASSWORD=" + SYNTHETIC_CREDENTIAL + "\n", content() + "OTHER=value\n",
            content().replace("connectTimeout=5000", "connectTimeout=0"),
            content().replace("socketTimeout=10000", "socketTimeout=10001"),
            content().replace("socketTimeout=10000", "socketTimeout=10000&allowLoadLocalInfile=true")}) {
            Files.writeString(path, invalid); assertRejected(path);
        }
    }

    static Stream<String> invalidReadOnlyPropagationOptions() {
        return Stream.of("", "&readOnlyPropagatesToServer=true", "&readOnlyPropagatesToServer=False",
            "&readOnlyPropagatesToServer=0", "&readOnlyPropagatesToServer=",
            "&readOnlyPropagatesToServer=false&readOnlyPropagatesToServer=false",
            "&readOnlyPropagatesToServer=false&readOnlyPropagatesToServer=true",
            "&readOnlyPropagatesToServer=%66alse", "&readOnlyPropagates%54oServer=false",
            "&readOnlyPropagatesToServer=false;unknown=true", "&readOnlyPropagatesToServer=false&",
            "&readOnlyPropagatesToServer=false&unknown=false");
    }
    @ParameterizedTest @MethodSource("invalidReadOnlyPropagationOptions")
    void requiresExactFalseAndRejectsMissingDuplicateEncodedOrUnknownOptions(String replacement) throws Exception {
        Path path = file();
        Files.writeString(path, content().replace("&readOnlyPropagatesToServer=false", replacement));
        assertRejected(path);
    }

    @Test void cliErrorsDoNotEchoAnyInputOrExceptionValues() {
        var bytes = new ByteArrayOutputStream();
        assertThat(DemoCleanupCommand.run(new String[] {"--password", "DO_NOT_ECHO_THIS_VALUE"}, new PrintStream(bytes))).isEqualTo(2);
        assertThat(bytes.toString()).contains("INPUT_REJECTED").doesNotContain("DO_NOT_ECHO", "Exception", "password");
    }

    @Test void onlyConfirmedVendorNowaitFailureIsBusy() {
        assertThat(DemoCleanupService.isExplicitNowait(new SQLException("not printed", "HY000", 3572))).isTrue();
        var parent = new SQLException("not printed");
        parent.setNextException(new SQLException("not printed", "HY000", 3572));
        assertThat(DemoCleanupService.isExplicitNowait(new IllegalStateException(parent))).isTrue();
        for (int code : new int[] {1205, 1213, 1045, 2006}) {
            assertThat(DemoCleanupService.isExplicitNowait(new SQLException("not printed", "HY000", code))).isFalse();
        }
        assertThat(DemoCleanupService.isExplicitNowait(new SQLException("not printed", "08000", 3572))).isFalse();
    }

    private Path file() throws Exception {
        Path directory = temporary.toRealPath().resolve("private");
        Files.createDirectory(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path path = directory.resolve("cleanup.env");
        Files.writeString(path, content());
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        return path;
    }
    private static void assertRejected(Path path) {
        assertThatThrownBy(() -> CleanupCredentials.read(path, "synthetic_check")).hasMessage("CREDENTIAL_FILE_REJECTED");
    }
    private static String content() {
        return "DB_URL=jdbc:mysql://127.0.0.1:13306/synthetic_check?sslMode=VERIFY_IDENTITY&connectTimeout=5000&socketTimeout=10000&readOnlyPropagatesToServer=false\n"
            + "DB_USERNAME=synthetic_cleanup_fixture\nDB_PASSWORD=" + SYNTHETIC_CREDENTIAL + "\nDEMO_MAX_VISITORS=1000\n";
    }
}
