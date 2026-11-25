package com.rc.novelreader.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppUpdateTest {
    @Test
    public void versionsCompareByNumber() {
        assertTrue(AppUpdate.newer("1.1", "1.0"));
        assertTrue(AppUpdate.newer("v1.10", "1.9"));
        assertTrue(AppUpdate.newer("1.0.1", "1.0"));
        assertTrue(AppUpdate.newer("2.0", "1.9.9"));
        assertFalse(AppUpdate.newer("1.0", "1.0"));
        assertFalse(AppUpdate.newer("1.0.0", "1.0"));
        assertFalse(AppUpdate.newer("v1.0", "1.0"));
        assertFalse(AppUpdate.newer("0.9", "1.0"));
        assertFalse(AppUpdate.newer("", "1.0"));
        assertFalse(AppUpdate.newer("latest", "1.0"));
        assertEquals(0, AppUpdate.compare("release-1.2", "1.2-debug"));
    }

    @Test
    public void releaseAnswerIsRead() {
        String json = "{\"tag_name\":\"1.1\",\"draft\":false,\"prerelease\":false,\"body\":\"Fixes\\n- one\","
                + "\"html_url\":\"https://code.example/o/r/releases/tag/1.1\",\"assets\":["
                + "{\"name\":\"notes.txt\",\"size\":10,\"browser_download_url\":\"https://code.example/notes.txt\"},"
                + "{\"name\":\"App-release.APK\",\"size\":1125407,\"browser_download_url\":\"https://code.example/a.apk\"}]}";
        AppUpdate.Release r = AppUpdate.parse(json);
        assertNotNull(r);
        assertEquals("1.1", r.tag);
        assertEquals("Fixes\n- one", r.notes);
        assertEquals("https://code.example/a.apk", r.apk);
        assertEquals(1125407, r.size);
        assertEquals("https://code.example/o/r/releases/tag/1.1", r.page);
    }

    @Test
    public void otherAnswersAreNoRelease() {
        assertNull(AppUpdate.parse("{\"message\":\"Not Found\"}"));
        assertNull(AppUpdate.parse("<html>rate limited</html>"));
        assertNull(AppUpdate.parse("{\"tag_name\":\"2.0\",\"prerelease\":true}"));
        assertNull(AppUpdate.parse("{\"tag_name\":\"2.0\",\"draft\":true}"));
        AppUpdate.Release bare = AppUpdate.parse("{\"tag_name\":\"2.0\",\"body\":null,"
                + "\"assets\":[{\"name\":\"a.apk\",\"browser_download_url\":\"http://plain.example/a.apk\"}]}");
        assertNotNull(bare);
        assertEquals("", bare.notes);
        assertEquals("", bare.apk);
    }
}
