package com.potg.don.maintenance;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public record CleanupOptions(Mode mode, int batchSize, Path configFile, String schema) {
    public enum Mode { DRY_RUN, VERIFY, APPLY }
    public static CleanupOptions parse(String[] args) {
        Mode mode = Mode.DRY_RUN;
        int batch = 10;
        Path config = null;
        String schema = null;
        boolean modeSet = false;
        Set<String> seen = new HashSet<>();
        try {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!seen.add(arg)) throw rejected();
                switch (arg) {
                    case "--verify", "--apply" -> {
                        if (modeSet) throw rejected();
                        modeSet = true;
                        mode = arg.equals("--apply") ? Mode.APPLY : Mode.VERIFY;
                    }
                    case "--batch-size" -> batch = Integer.parseInt(args[++i]);
                    case "--config-file" -> config = Path.of(args[++i]);
                    case "--schema" -> schema = args[++i];
                    default -> throw rejected();
                }
            }
        } catch (RuntimeException error) { throw rejected(); }
        if (batch < 1 || batch > 100 || config == null || !config.isAbsolute()
            || schema == null || !schema.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw rejected();
        return new CleanupOptions(mode, batch, config.normalize(), schema);
    }
    @Override public String toString() { return "CleanupOptions[redacted]"; }
    private static CleanupFailure rejected() { return new CleanupFailure(CleanupFailure.Code.INPUT_REJECTED); }
}
