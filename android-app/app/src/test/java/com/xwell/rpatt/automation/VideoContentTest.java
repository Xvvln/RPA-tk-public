package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;

public final class VideoContentTest {
    @Test
    public void extractsOnlyCurrentOnScreenPagerItem() {
        UiSnapshot snapshot = snapshot(
                node(":id/title", "Nathan", "", true, 36, -220, 300, -150),
                node(":id/desc", "previous video", "", true, 36, -140, 840, -30),
                node(":id/title", "Luca's shots", "", true, 36, 1992, 335, 2058),
                node(":id/desc", "I love the lights of NY", "", true, 36, 2070, 840, 2178),
                node(":id/oyn", "", "音乐：Luca 创作的 Original sound", true, 888, 2019, 1080, 2205),
                node(":id/title", "MrBeast", "", true, 36, 4118, 289, 4205));

        VideoContent content = VideoContent.from(snapshot);

        assertEquals("Luca's shots", content.author);
        assertEquals("I love the lights of NY", content.description);
        assertEquals("Luca 创作的 Original sound", content.music);
    }

    @Test
    public void ignoresAccessibilityNodeMarkedNotVisible() {
        UiSnapshot snapshot = snapshot(
                node(":id/title", "recycled", "", false, 36, 1900, 300, 1970),
                node(":id/title", "current", "", true, 36, 1992, 335, 2058),
                node(":id/desc", "current description", "", true, 36, 2070, 840, 2178));

        assertEquals("current", VideoContent.from(snapshot).author);
    }

    @Test
    public void writeIdentityRequiresAuthorAndDescription() {
        VideoContent stable = new VideoContent("author", "description", "music");
        VideoContent missingDescription = new VideoContent("author", "", "music");

        assertTrue(stable.hasStableActionIdentity());
        assertEquals("author\ndescription\nmusic", stable.actionFingerprint());
        assertFalse(missingDescription.hasStableActionIdentity());
        assertEquals("", missingDescription.actionFingerprint());
    }

    private static UiSnapshot snapshot(UiNode... nodes) {
        return new UiSnapshot(1, PageAnalyzer.TIKTOK_PACKAGE, 1080, 2400, Arrays.asList(nodes));
    }

    private static UiNode node(String suffix, String text, String description, boolean visible,
                               int left, int top, int right, int bottom) {
        Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return new UiNode(PageAnalyzer.TIKTOK_PACKAGE + suffix, text, description, "android.view.View",
                PageAnalyzer.TIKTOK_PACKAGE, false, true, visible, bounds);
    }
}
