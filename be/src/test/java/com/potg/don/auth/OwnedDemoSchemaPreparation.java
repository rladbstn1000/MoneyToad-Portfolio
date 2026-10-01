package com.potg.don.auth;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

/** Explicit DDL only in disposable loopback schemas; product always starts with validate. */
public final class OwnedDemoSchemaPreparation {
    private OwnedDemoSchemaPreparation() { }
    public static void recreate(Map<String, Object> inputs, boolean demo) {
        recreate(inputs.get("DB_URL").toString(), inputs.get("DB_USERNAME").toString(),
            inputs.get("DB_PASSWORD").toString(), demo);
    }
    public static void recreate(String url, String username, String password, boolean demo) {
        try {
            URI uri = URI.create(url.substring("jdbc:".length()));
            if (!url.startsWith("jdbc:mysql://") || !List.of("127.0.0.1", "localhost").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getPort() <= 0 || uri.getPort() == 3306
                || !uri.getPath().matches("/moneytoad_(a1|render)_[a-z0-9_]+")) {
                throw new IllegalArgumentException("OWNED_LOCAL_SCHEMA_REQUIRED");
            }
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
                for (String table : List.of("demo_visit", "demo_capacity", "demo_admission_lock", "transactions", "budgets", "cards",
                    "analysis_job", "peer_transaction_stats", "dummy", "users")) {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                }
                Path baselinePath = Path.of("../scripts/verification/fixtures/managed-provider-schema.sql");
                if (!Files.isRegularFile(baselinePath)) baselinePath = Path.of("scripts/verification/fixtures/managed-provider-schema.sql");
                String baseline = Files.readString(baselinePath);
                execute(statement, baseline);
                if (demo) {
                    for (String migration : List.of("V001__demo_admission.sql", "V002__demo_admission_lock.sql")) {
                        try (var source = OwnedDemoSchemaPreparation.class.getResourceAsStream("/db/demo/" + migration)) {
                            if (source == null) throw new IllegalStateException("DEMO_MIGRATION_REQUIRED");
                            execute(statement, new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        } catch (Exception failure) {
            // Never put URL, schema identity or synthetic credentials in test failure output.
            var causes = new java.util.ArrayList<String>();
            var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
            for (Throwable item = failure; item != null && seen.add(item); item = item.getCause()) {
                causes.add(item.getClass().getSimpleName());
            }
            throw new IllegalStateException("OWNED_SCHEMA_PREPARATION_FAILED " + String.join(" ", causes));
        }
    }
    private static void execute(java.sql.Statement statement, String sql) throws java.sql.SQLException {
        String source = sql.replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
        for (String command : source.split(";")) if (!command.isBlank()) statement.execute(command);
    }
}
