package com.potg.don.maintenance;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** No SpringApplication, Redis client, scheduler or product endpoint is started. */
public final class DemoCleanupCommand {
    private DemoCleanupCommand() { }
    public static void main(String[] args) { System.exit(run(args, System.out)); }
    static int run(String[] args, PrintStream output) {
        if (args.length == 1 && "--help".equals(args[0])) {
            output.println("Usage: --config-file ABSOLUTE_PRIVATE_FILE --schema EXACT_SCHEMA [--verify|--apply] [--batch-size 1..100]");
            output.println("Default: read-only dry-run, batch 10. Apply never retries. Secrets belong only in the private file.");
            return 0;
        }
        Connection connection = null;
        DemoCleanupService.Result result = null;
        CleanupFailure.Code failure = null;
        try {
            CleanupOptions options = CleanupOptions.parse(args);
            CleanupCredentials credentials = CleanupCredentials.read(options.configFile(), options.schema());
            Properties properties = new Properties();
            properties.setProperty("user", credentials.username());
            properties.setProperty("password", credentials.password());
            // No driver logger or statement dump is configured, and no URL contains credentials.
            properties.setProperty("logger", "com.mysql.cj.log.NullLogger");
            connection = DriverManager.getConnection(credentials.url(), properties);
            if (!options.schema().equals(connection.getCatalog())) throw new CleanupFailure(CleanupFailure.Code.SCHEMA_REJECTED);
            result = new DemoCleanupService().execute(connection, options.mode(), options.batchSize(), credentials.expectedMax());
        } catch (CleanupFailure error) { failure = error.code(); }
        catch (SQLException error) { failure = CleanupFailure.Code.SQL_FAILURE; }
        catch (RuntimeException error) { failure = CleanupFailure.Code.UNEXPECTED_FAILURE; }
        finally {
            if (connection != null) {
                try { connection.close(); }
                catch (SQLException error) { if (failure == null) failure = CleanupFailure.Code.CONNECTION_CLOSE_UNKNOWN; }
            }
        }
        if (failure != null) {
            boolean unknown = failure == CleanupFailure.Code.COMMIT_UNKNOWN || failure == CleanupFailure.Code.ROLLBACK_UNKNOWN
                || failure == CleanupFailure.Code.CONNECTION_CLOSE_UNKNOWN;
            output.println("{\"status\":\"" + (unknown ? "UNKNOWN" : "FAIL") + "\",\"code\":\"" + failure.name() + "\"}");
            return unknown ? 3 : 2;
        }
        output.println("{\"status\":\"PASS\",\"mode\":\"" + result.mode() + "\",\"candidates\":" + result.candidates()
            + ",\"deleted\":" + result.deleted() + ",\"count_before\":" + result.countBefore()
            + ",\"count_after\":" + result.countAfter() + ",\"maximum\":" + result.maximum() + "}");
        return 0;
    }
}
