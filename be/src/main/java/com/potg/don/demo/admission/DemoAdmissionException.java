package com.potg.don.demo.admission;

/** Fixed public classifications. SQL messages, identifiers and credentials are never response data. */
public final class DemoAdmissionException extends RuntimeException {
    public enum Code { DEMO_CAPACITY_FULL, DEMO_ADMISSION_BUSY, DEMO_ADMISSION_UNAVAILABLE }
    private final Code code;
    public DemoAdmissionException(Code code) {
        super(code.name());
        this.code = code;
    }
    public Code code() { return code; }
}
