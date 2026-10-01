package com.potg.verification.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** No service connections or credentials: fail-closed input/file/DDL boundary only. */
class ManagedProviderProbeSafetyTest {
    @TempDir Path temporary;

    private Map<String, String> valid() {
        Map<String, String> values = new HashMap<>();
        for (String field : ManagedProviderProbe.INPUTS) values.put(field, "fixture-only");
        values.put("TIDB_HOST", "db.example.invalid"); values.put("REDIS_HOST", "redis.example.invalid");
        values.put("TIDB_PORT", "4000"); values.put("REDIS_PORT", "6380"); values.put("appPort", "18443");
        values.put("REDIS_SSL_ENABLED", "true"); values.put("mode", "managed");
        values.put("schema", "moneytoad_contract_0123456789abcdef");
        values.put("deadlineEpochMillis", Long.toString(System.currentTimeMillis() + 600000));
        return values;
    }

    @Test void acceptsExplicitManagedInputsWithoutConnecting() {
        ManagedProviderProbe.validateInputs(valid());
    }
    @Test void acceptsExplicitOwnedLoopbackRehearsalWithoutClaimingManaged() {
        var values = valid(); values.put("mode", "local-rehearsal");
        values.put("TIDB_HOST", "127.0.0.1"); values.put("REDIS_HOST", "localhost");
        ManagedProviderProbe.validateInputs(values);
    }
    @ParameterizedTest
    @CsvSource({"mode,automatic", "TIDB_HOST,127.0.0.1", "REDIS_HOST,localhost", "TIDB_HOST,1.2.3.4",
        "TIDB_HOST,db.example.invalid/path", "REDIS_HOST,redis..example.invalid", "REDIS_SSL_ENABLED,false",
        "TIDB_PORT,0", "REDIS_PORT,65536", "appPort,-1", "schema,moneytoad_production",
        "schema,moneytoad_contract_0123456789abcdeF", "deadlineEpochMillis,1"})
    void rejectsInvalidOrUnboundedInputs(String field, String bad) {
        var values = valid(); values.put(field, bad);
        assertThatThrownBy(() -> ManagedProviderProbe.validateInputs(values))
            .isInstanceOf(ManagedSqlContracts.ContractFailure.class);
    }
    @Test void rejectsUnknownAndMissingKeys() {
        var extra = valid(); extra.put("extra", "unused");
        assertThatThrownBy(() -> ManagedProviderProbe.validateInputs(extra)).hasMessage("INPUT_ALLOWLIST");
        var missing = valid(); missing.remove("REDIS_PASSWORD");
        assertThatThrownBy(() -> ManagedProviderProbe.validateInputs(missing)).hasMessage("INPUT_ALLOWLIST");
    }
    @Test void rejectsBlankInputAndPastOrUnlimitedDeadline() {
        var blank = valid(); blank.put("TIDB_SETUP_PASSWORD", " ");
        assertThatThrownBy(() -> ManagedProviderProbe.validateInputs(blank)).hasMessage("INPUT_REQUIRED");
        for (long deadline : new long[] {System.currentTimeMillis() - 1000, System.currentTimeMillis() + 3600000}) {
            var values = valid(); values.put("deadlineEpochMillis", Long.toString(deadline));
            assertThatThrownBy(() -> ManagedProviderProbe.validateInputs(values)).hasMessage("FINITE_DEADLINE");
        }
    }
    @Test void privateInputRequiresRegularSingleLinkOwnedFileAndPrivateDirectory() throws Exception {
        temporary = temporary.toRealPath();
        Files.setPosixFilePermissions(temporary, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Path value = temporary.resolve("input.json"); Files.writeString(value, "{}");
        Files.setPosixFilePermissions(value, ManagedProviderProbe.PRIVATE_FILE);
        assertThat(ManagedProviderProbe.privateFile(value.toString())).isEqualTo(value.toAbsolutePath().normalize());
        Files.setPosixFilePermissions(value, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));
        assertThatThrownBy(() -> ManagedProviderProbe.privateFile(value.toString())).hasMessage("PRIVATE_FILE_MODE");
        Files.setPosixFilePermissions(value, ManagedProviderProbe.PRIVATE_FILE);
        Path hardlink = temporary.resolve("hardlink.json"); Files.createLink(hardlink, value);
        assertThatThrownBy(() -> ManagedProviderProbe.privateFile(value.toString())).hasMessage("NO_HARDLINK");
        Files.delete(hardlink);
        Path symlink = temporary.resolve("symlink.json"); Files.createSymbolicLink(symlink, value);
        assertThatThrownBy(() -> ManagedProviderProbe.privateFile(symlink.toString())).hasMessage("PRIVATE_FILE_MODE");
    }
    @Test void managedRuntimeAccountPreservesExactPrefixAndFitsTidbLimit() {
        String entropy = "0123456789abcdef01234567";
        assertThat(ManagedProviderProbe.runtimeAccountName("managed", "Cluster7.root", entropy))
            .isEqualTo("Cluster7.m0123456789abcd");
        assertThat(ManagedProviderProbe.runtimeAccountName("managed", "abcdefghijklmnop.setup_user", entropy))
            .isEqualTo("abcdefghijklmnop.m0123456789abcd").hasSize(32);
    }
    @Test void localRuntimeAccountNamingIsUnchanged() {
        assertThat(ManagedProviderProbe.runtimeAccountName("local-rehearsal", "root", "0123456789abcdef01234567"))
            .isEqualTo("mtc_0123456789abcdef01234567");
    }
    @ParameterizedTest
    @CsvSource({"root", ".root", "prefix.", "prefix.extra.root", "prefix'bad.root", "abcdefghijklmnopq.root"})
    void rejectsMissingUnsafeOrOversizedManagedPrefix(String setupUsername) {
        assertThatThrownBy(() -> ManagedProviderProbe.runtimeAccountName("managed", setupUsername, "0123456789abcdef01234567"))
            .hasMessage("TIDB_STARTER_USERNAME_PREFIX");
    }
    @Test void runtimeAccountRequiresKnownModeAndGeneratedEntropy() {
        assertThatThrownBy(() -> ManagedProviderProbe.runtimeAccountName("other", "prefix.root", "0123456789abcdef01234567"))
            .hasMessage("MODE");
        assertThatThrownBy(() -> ManagedProviderProbe.runtimeAccountName("managed", "prefix.root", "not-generated"))
            .hasMessage("RUNTIME_ACCOUNT_ENTROPY");
    }
    @Test void rejectsChangedDdlBeforeAnyDatabaseConnection() throws Exception {
        temporary = temporary.toRealPath();
        Path ddl = temporary.resolve("managed-provider-schema.sql"); Files.writeString(ddl, "CREATE TABLE unrelated (id INT);");
        assertThatThrownBy(() -> ManagedSqlContracts.reviewedStatements(ddl)).hasMessage("REVIEWED_DDL_DIGEST");
    }
}
