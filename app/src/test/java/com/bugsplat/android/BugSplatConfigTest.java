package com.bugsplat.android;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class BugSplatConfigTest {

    @Before
    public void setUp() {
        BugSplatConfig.reset();
    }

    @After
    public void tearDown() {
        BugSplatConfig.reset();
    }

    @Test
    public void isInitialized_falseUntilInit() {
        assertFalse(BugSplatConfig.isInitialized());

        BugSplatConfig.init("db", "app", "1.0.0", null, null);

        assertTrue(BugSplatConfig.isInitialized());
        assertEquals("db", BugSplatConfig.database());
        assertEquals("app", BugSplatConfig.application());
        assertEquals("1.0.0", BugSplatConfig.version());
    }

    @Test
    public void init_copiesAttributesAndSkipsNullEntries() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("env", "prod");
        attributes.put("missing", null);

        BugSplatConfig.init("db", "app", "1.0.0", attributes, null);

        Map<String, String> snapshot = BugSplatConfig.attributesSnapshot();
        assertEquals("prod", snapshot.get("env"));
        assertFalse(snapshot.containsKey("missing"));

        // the snapshot is a copy — mutating it must not affect the config
        snapshot.put("env", "dev");
        assertEquals("prod", BugSplatConfig.attributesSnapshot().get("env"));
    }

    @Test
    public void init_replacesAttributesFromAPriorInit() {
        BugSplatConfig.init("db", "app", "1.0.0",
                java.util.Collections.singletonMap("stale", "yes"), null);
        BugSplatConfig.init("db", "app", "2.0.0", null, null);

        assertTrue(BugSplatConfig.attributesSnapshot().isEmpty());
        assertEquals("2.0.0", BugSplatConfig.version());
    }

    @Test
    public void setAndRemoveAttribute_updateTheSnapshot() {
        BugSplatConfig.init("db", "app", "1.0.0", null, null);

        BugSplatConfig.setAttribute("level", "3");
        assertEquals("3", BugSplatConfig.attributesSnapshot().get("level"));

        BugSplatConfig.removeAttribute("level");
        assertFalse(BugSplatConfig.attributesSnapshot().containsKey("level"));
    }

    @Test
    public void attachmentFiles_mapsPathsAndSkipsBlanks() {
        BugSplatConfig.init("db", "app", "1.0.0", null,
                new String[]{"/tmp/one.log", null, "", "/tmp/two.log"});

        List<File> files = BugSplatConfig.attachmentFiles();

        assertEquals(Arrays.asList(new File("/tmp/one.log"), new File("/tmp/two.log")), files);
    }

    @Test
    public void attachmentFiles_emptyWhenNoAttachments() {
        BugSplatConfig.init("db", "app", "1.0.0", null, null);

        assertTrue(BugSplatConfig.attachmentFiles().isEmpty());
    }
}
