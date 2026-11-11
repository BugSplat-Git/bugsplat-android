package com.bugsplat.android;

import android.app.ApplicationExitInfo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.*;

public class TombstoneReporterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---- dedup ----

    @Test
    public void isUnreported_onlyNativeCrashesNewerThanLastReported() {
        int nativeCrash = ApplicationExitInfo.REASON_CRASH_NATIVE;

        assertTrue(TombstoneReporter.isUnreported(nativeCrash, 1001, 1000));
        assertFalse(TombstoneReporter.isUnreported(nativeCrash, 1000, 1000));
        assertFalse(TombstoneReporter.isUnreported(nativeCrash, 999, 1000));
        assertFalse(TombstoneReporter.isUnreported(ApplicationExitInfo.REASON_ANR, 1001, 1000));
        assertFalse(TombstoneReporter.isUnreported(ApplicationExitInfo.REASON_CRASH, 1001, 1000));
    }

    @Test
    public void reportedThrough_holdsBackATimestampSharedWithALaterCrash() {
        int nativeCrash = ApplicationExitInfo.REASON_CRASH_NATIVE;
        int anr = ApplicationExitInfo.REASON_ANR;
        long[] timestamps = {1000, 2000, 2000, 3000};
        int[] reasons = {nativeCrash, nativeCrash, nativeCrash, anr};

        assertEquals(1000, TombstoneReporter.reportedThrough(timestamps, reasons, 0, 500));
        // The crash at index 2 has the same timestamp and is not done yet.
        assertEquals(1999, TombstoneReporter.reportedThrough(timestamps, reasons, 1, 500));
        assertEquals(2000, TombstoneReporter.reportedThrough(timestamps, reasons, 2, 500));

        // A non-crash record with the same timestamp does not hold it back.
        long[] withAnr = {2000, 2000};
        int[] anrReasons = {nativeCrash, anr};
        assertEquals(2000, TombstoneReporter.reportedThrough(withAnr, anrReasons, 0, 500));
    }

    // ---- attachments ----

    @Test
    public void copy_stopsAtTheLimit() throws IOException {
        byte[] source = new byte[10_000];
        Arrays.fill(source, (byte) 7);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        TombstoneReporter.copy(new ByteArrayInputStream(source), out, new byte[4096], 5_000);

        assertEquals(5_000, out.size());
    }

    @Test
    public void zip_skipsAttachmentsPastTheSizeLimit() throws IOException {
        File small = tmp.newFile("small.txt");
        Files.write(small.toPath(), new byte[100]);
        File large = tmp.newFile("large.txt");
        Files.write(large.toPath(), new byte[300]);
        File fits = tmp.newFile("fits.txt");
        Files.write(fits.toPath(), new byte[150]);

        byte[] zipped = TombstoneReporter.zip(new byte[]{1, 2, 3},
                Arrays.asList(small.getPath(), large.getPath(), fits.getPath()), 256);

        ArrayList<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipped))) {
            for (ZipEntry e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertEquals(Arrays.asList(TombstoneReporter.TOMBSTONE_ENTRY, "small.txt", "fits.txt"), names);
    }

    // ---- session cleanup ----

    @Test
    public void deleteSessionsExcept_keepsOnlyTheGivenPids() throws IOException {
        File dir = tmp.getRoot();
        File kept = tmp.newFile("100.json");
        File exited = tmp.newFile("200.json");
        File leftoverTmp = tmp.newFile("300.json.tmp");
        File other = tmp.newFile("notes.txt");
        File startedDuringScan = tmp.newFile("400.json");
        long scanStart = System.currentTimeMillis() - 1000;
        for (File f : new File[]{kept, exited, leftoverTmp, other}) {
            assertTrue(f.setLastModified(scanStart - 60_000));
        }
        assertTrue(startedDuringScan.setLastModified(scanStart + 500));

        TombstoneReporter.deleteSessionsExcept(dir, new HashSet<>(Arrays.asList(100)), scanStart);

        assertTrue(kept.exists());
        assertFalse(exited.exists());
        assertFalse(leftoverTmp.exists());
        assertTrue(other.exists());
        assertTrue(startedDuringScan.exists());
    }

    // ---- metadata selection ----

    @Test
    public void load_returnsValuesPersistedByTheCrashedProcess() throws Exception {
        File dir = tmp.getRoot();
        CrashSession crashed = new CrashSession(CrashSession.fileFor(dir, 1234), "fred", "app", "1.0");
        crashed.setAttribute("level", "2");
        crashed.setAttribute("BugSplatUser", "Fred");
        crashed.addAttachment("/data/log.txt");

        // A later session with other values must not affect what is reported for 1234.
        CrashSession next = new CrashSession(CrashSession.fileFor(dir, 5678), "fred", "app", "2.0");
        next.setAttribute("level", "9");

        CrashSession loaded = CrashSession.load(dir, 1234, System.currentTimeMillis() + 1000);

        assertNotNull(loaded);
        assertEquals("fred", loaded.database);
        assertEquals("app", loaded.application);
        assertEquals("1.0", loaded.version);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("level", "2");
        expected.put("BugSplatUser", "Fred");
        assertEquals(expected, loaded.attributes());
        assertEquals(Arrays.asList("/data/log.txt"), loaded.attachments());
    }

    @Test
    public void load_reflectsRemovals() {
        File dir = tmp.getRoot();
        CrashSession session = new CrashSession(CrashSession.fileFor(dir, 1), "fred", "app", "1.0");
        session.setAttribute("a", "1");
        session.setAttribute("a", null);
        session.addAttachment("/data/log.txt");
        session.removeAttachment("/data/log.txt");

        CrashSession loaded = CrashSession.load(dir, 1, System.currentTimeMillis() + 1000);

        assertNotNull(loaded);
        assertTrue(loaded.attributes().isEmpty());
        assertTrue(loaded.attachments().isEmpty());
    }

    @Test
    public void load_returnsNullForAnotherPid() {
        File dir = tmp.getRoot();
        new CrashSession(CrashSession.fileFor(dir, 1234), "fred", "app", "1.0").setAttribute("a", "1");

        assertNull(CrashSession.load(dir, 4321, System.currentTimeMillis() + 1000));
    }

    @Test
    public void load_ignoresAFileWrittenAfterTheExit() {
        File dir = tmp.getRoot();
        new CrashSession(CrashSession.fileFor(dir, 1234), "fred", "app", "2.0").setAttribute("a", "1");
        File file = CrashSession.fileFor(dir, 1234);
        assertTrue(file.setLastModified(5000));

        // The PID was reused by a process that started after the crash at 4000.
        assertNull(CrashSession.load(dir, 1234, 4000));
        assertNotNull(CrashSession.load(dir, 1234, 5000));
    }

    // ---- zip ----

    @Test
    public void zip_containsTombstoneAndExistingAttachments() throws IOException {
        File log = tmp.newFile("log.txt");
        Files.write(log.toPath(), "log".getBytes(StandardCharsets.UTF_8));
        File sameName = new File(tmp.newFolder("other"), "log.txt");
        Files.write(sameName.toPath(), "other".getBytes(StandardCharsets.UTF_8));
        String missing = new File(tmp.getRoot(), "missing.txt").getAbsolutePath();

        byte[] tombstone = {0x0a, 0x05, 0x61, 0x72, 0x6d, 0x36, 0x34};
        byte[] zipped = TombstoneReporter.zip(tombstone,
                Arrays.asList(log.getAbsolutePath(), missing, sameName.getAbsolutePath()));

        Map<String, byte[]> entries = unzip(zipped);
        assertEquals(Arrays.asList("tombstone.pb", "log.txt"), new ArrayList<>(entries.keySet()));
        assertArrayEquals(tombstone, entries.get("tombstone.pb"));
        assertEquals("log", new String(entries.get("log.txt"), StandardCharsets.UTF_8));
    }

    private static Map<String, byte[]> unzip(byte[] zipped) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipped))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = zis.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                entries.put(entry.getName(), baos.toByteArray());
            }
        }
        return entries;
    }
}
