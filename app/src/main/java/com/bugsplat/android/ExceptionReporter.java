package com.bugsplat.android;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Uploads caught {@link Throwable}s to BugSplat as non-fatal reports.
 *
 * <p>The body is the same XML report the BugSplat Java SDK posts under crash
 * type id 4 — a {@code <report>} document whose frames carry symbol, file, and
 * line for each {@link StackTraceElement}. It is zipped as {@code stack.jdmp},
 * matching the entry name the server expects for that crash type. Attributes,
 * attachments, and the database/application/version triple come from
 * {@link BugSplatConfig} so a non-fatal carries the same metadata as a crash.</p>
 *
 * <p>Reports are gated by {@link #shouldPost(long)}: a caught exception inside a
 * render or game loop can fire every frame, so posts closer together than
 * {@link #DEFAULT_MIN_POST_INTERVAL_MS} are dropped. This mirrors the
 * client-side guard in the BugSplat Unity SDK.</p>
 */
class ExceptionReporter {
    private static final String TAG = "BugSplat";
    static final String CRASH_TYPE = "Android.Java";
    static final int CRASH_TYPE_ID = 4;
    static final String ENTRY_NAME = "stack.jdmp";

    /** Upper bound on how far a cause chain is walked. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** Default minimum gap between two accepted posts. */
    static final long DEFAULT_MIN_POST_INTERVAL_MS = 3_000L;

    private static final AtomicLong minPostIntervalMs = new AtomicLong(DEFAULT_MIN_POST_INTERVAL_MS);
    /** Timestamp of the last accepted post; 0 means none yet. */
    private static final AtomicLong lastPostMs = new AtomicLong(0L);

    private final ReportUploader uploader;

    ExceptionReporter(String database, String application, String version) {
        this(new ReportUploader(database, application, version));
    }

    /** Package-private constructor for testing with a custom uploader. */
    ExceptionReporter(ReportUploader uploader) {
        this.uploader = uploader;
    }

    // -- Rate limiting --

    /**
     * Set the minimum interval between accepted posts. A value of zero or less
     * disables the guard.
     */
    static void setMinPostIntervalMillis(long millis) {
        minPostIntervalMs.set(millis);
    }

    static long getMinPostIntervalMillis() {
        return minPostIntervalMs.get();
    }

    /**
     * Claim a post slot for {@code nowMs}. Returns true — and records the
     * timestamp — when the caller may post; false when the previous post was
     * too recent.
     */
    static boolean shouldPost(long nowMs) {
        long interval = minPostIntervalMs.get();
        if (interval <= 0) {
            lastPostMs.set(nowMs);
            return true;
        }
        while (true) {
            long last = lastPostMs.get();
            if (last != 0L && nowMs - last < interval) {
                return false;
            }
            if (lastPostMs.compareAndSet(last, nowMs)) {
                return true;
            }
        }
    }

    /** Test hook — forgets the last post so the next one is always allowed. */
    static void resetRateLimit() {
        lastPostMs.set(0L);
        minPostIntervalMs.set(DEFAULT_MIN_POST_INTERVAL_MS);
    }

    // -- Upload --

    /**
     * Build and upload a non-fatal report for {@code throwable}.
     *
     * @param throwable the caught exception (must not be null)
     * @param attributes per-call attributes, merged over the init attributes
     * @param attachments files to include alongside the report, or null
     * @return true if the report was uploaded
     */
    boolean post(Throwable throwable, Map<String, String> attributes, List<File> attachments) {
        if (throwable == null) {
            Log.e(TAG, "postException called with a null throwable");
            return false;
        }

        try {
            byte[] report = buildXmlReport(throwable).getBytes(StandardCharsets.UTF_8);
            byte[] zipped = ReportUploader.zip(ENTRY_NAME, report, attachments);

            CommitOptions options = new CommitOptions()
                    .crashType(CRASH_TYPE)
                    .crashTypeId(CRASH_TYPE_ID)
                    .description(throwable.toString())
                    .attributes(mergeAttributes(attributes));

            if (uploader.upload(zipped, options)) {
                Log.i(TAG, "Exception reported: " + throwable);
                return true;
            }
            Log.e(TAG, "Failed to report exception: " + throwable);
            return false;
        } catch (IOException e) {
            Log.e(TAG, "Failed to report exception", e);
            return false;
        }
    }

    /** Init attributes with the per-call attributes layered on top. */
    private static Map<String, String> mergeAttributes(Map<String, String> callAttributes) {
        Map<String, String> merged = new LinkedHashMap<>(BugSplatConfig.attributesSnapshot());
        if (callAttributes != null) {
            for (Map.Entry<String, String> entry : callAttributes.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return merged;
    }

    // -- Report body --

    /**
     * Render {@code throwable} as a BugSplat XML report.
     *
     * <p>Frames come from the root cause — the innermost {@code getCause()} — so
     * reports group by where the failure actually originated, matching the Java
     * SDK. The full chain, wrappers included, is preserved in
     * {@code <explanation>}.</p>
     */
    static String buildXmlReport(Throwable throwable) {
        Throwable root = rootCause(throwable);
        StackTraceElement[] stack = root.getStackTrace();

        StringBuilder sb = new StringBuilder(1024);
        sb.append("<report>\n");
        sb.append(" <process>\n");
        sb.append("  <exception>\n");
        sb.append("   <func>").append(cdata(stackKey(root, stack))).append("</func>\n");
        sb.append("   <code>").append(cdata(root.getMessage())).append("</code>\n");
        sb.append("   <explanation>").append(cdata(describeChain(throwable))).append("</explanation>\n");
        sb.append("   <file>").append(escape(fileNameOf(stack))).append("</file>\n");
        sb.append("   <line>").append(lineNumberOf(stack)).append("</line>\n");
        sb.append("   <registers></registers>\n");
        sb.append("  </exception>\n");
        sb.append("  <modules numloaded=\"0\"></modules>\n");
        sb.append("  <threads count=\"1\">\n");
        sb.append("   <thread id=\"1\" current=\"yes\" event=\"yes\" framecount=\"")
                .append(stack.length).append("\">\n");

        for (StackTraceElement frame : stack) {
            sb.append("    <frame>\n");
            sb.append("     <symbol>").append(cdata(symbolOf(frame))).append("</symbol>\n");
            sb.append("     <arguments></arguments>\n");
            sb.append("     <locals></locals>\n");
            sb.append("     <file>").append(escape(frame.getFileName())).append("</file>\n");
            sb.append("     <line>").append(frame.getLineNumber()).append("</line>\n");
            sb.append("    </frame>\n");
        }

        sb.append("   </thread>\n");
        sb.append("  </threads>\n");
        sb.append(" </process>\n");
        sb.append("</report>\n");
        return sb.toString();
    }

    /** Walk to the innermost cause, bounded so a self-referential chain can't spin. */
    static Throwable rootCause(Throwable throwable) {
        Throwable root = throwable;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable cause = root.getCause();
            if (cause == null || cause == root) {
                break;
            }
            root = cause;
        }
        return root;
    }

    /**
     * The value BugSplat groups on. Uses the top frame's class and method; a
     * throwable with no stack trace (possible when the JVM elides it) falls back
     * to the exception's own class name so the report still groups sensibly.
     */
    private static String stackKey(Throwable root, StackTraceElement[] stack) {
        return stack.length > 0 ? symbolOf(stack[0]) : root.getClass().getName();
    }

    private static String symbolOf(StackTraceElement frame) {
        return frame.getClassName() + "." + frame.getMethodName();
    }

    private static String fileNameOf(StackTraceElement[] stack) {
        return stack.length > 0 ? stack[0].getFileName() : null;
    }

    private static int lineNumberOf(StackTraceElement[] stack) {
        return stack.length > 0 ? stack[0].getLineNumber() : 0;
    }

    /** {@code toString()} of the throwable and each of its causes, outermost first. */
    private static String describeChain(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (depth > 0) {
                sb.append("\nCaused by: ");
            }
            sb.append(current);
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
            depth++;
        }
        return sb.toString();
    }

    /**
     * Wrap {@code value} in a CDATA section. A literal {@code ]]>} in the value
     * would close the section early, so it is split across two sections.
     */
    private static String cdata(String value) {
        String safe = value == null ? "" : value.replace("]]>", "]]]]><![CDATA[>");
        return "<![CDATA[" + safe + "]]>";
    }

    /** Escape the few characters that are illegal in element text. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
