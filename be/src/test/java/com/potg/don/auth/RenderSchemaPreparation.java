package com.potg.don.auth;

/** Explicit reviewed DDL in disposable loopback schema; no Spring/JPA startup. */
public final class RenderSchemaPreparation {
    private RenderSchemaPreparation() { }
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("NO_ARGUMENTS_ALLOWED");
        OwnedDemoSchemaPreparation.recreate(System.getenv("RUNNER_RENDER_DB_URL"),
            System.getenv("RUNNER_RENDER_DB_USERNAME"), System.getenv("RUNNER_RENDER_DB_PASSWORD"), true);
        System.out.println("RENDER_SCHEMA_PREPARATION_PASS");
    }
}
