package org.openucx.rdma;

import java.io.File;

final class UcxNative {
    private static boolean loaded = false;

    private UcxNative() {
    }

    static synchronized void load() {
        if (loaded) {
            return;
        }

        UnsatisfiedLinkError lastError = null;
        String[] candidates = new String[] {
                System.getenv("JUCX_LIB_PATH"),
                resolveFromEnv("JUCX_HOME"),
                resolveFromEnv("UCX_HOME"),
                "/opt/ucx/lib/libjucx.so",
                "/opt/ucx/lib64/libjucx.so"
        };

        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            File lib = new File(candidate);
            if (!lib.exists()) {
                continue;
            }
            try {
                System.load(lib.getAbsolutePath());
                loaded = true;
                System.out.println("Loaded JUCX native library: " + lib.getAbsolutePath());
                return;
            } catch (UnsatisfiedLinkError e) {
                lastError = e;
            }
        }

        try {
            System.loadLibrary("jucx");
            loaded = true;
            System.out.println("Loaded JUCX native library from java.library.path");
            return;
        } catch (UnsatisfiedLinkError e) {
            lastError = e;
        }

        if (lastError != null) {
            throw lastError;
        }
    }

    private static String resolveFromEnv(String envVar) {
        String base = System.getenv(envVar);
        if (base == null || base.isEmpty()) {
            return null;
        }
        return base + "/lib/libjucx.so";
    }
}
