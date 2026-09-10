package com.potg.don.auth.demo;

/** Fixed reasons only: never include tokens, session identifiers or Redis details. */
public class DemoAuthException extends RuntimeException {

	public enum Reason {
		INVALID_DEMO_TOKEN, DEMO_SESSION_INVALID, DEMO_REFRESH_REUSED, DEMO_SESSION_UNAVAILABLE
	}

	private final Reason reason;

	public DemoAuthException(Reason reason) {
		super(reason.name());
		this.reason = reason;
	}

	public Reason reason() { return reason; }
}
