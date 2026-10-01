package com.potg.don.maintenance;

/** No database messages, causes or input values are retained at the CLI boundary. */
public final class CleanupFailure extends RuntimeException {
    public enum Code {
        INPUT_REJECTED, CREDENTIAL_FILE_REJECTED, SCHEMA_REJECTED,
        INTEGRITY_REJECTED, UNSAFE_BATCH, LOCK_BUSY, SQL_FAILURE, READ_ONLY_GUARD_REJECTED,
        ROW_COUNT_MISMATCH, ROLLBACK_UNKNOWN, COMMIT_UNKNOWN, CONNECTION_CLOSE_UNKNOWN,
        UNEXPECTED_FAILURE
    }
    private final Code code;
    public CleanupFailure(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
