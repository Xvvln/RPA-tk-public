package com.xwell.rpatt.automation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VideoContentIdentityTest {
    @Test
    public void truncatedAndExpandedDescriptionRemainTheSameVideo() {
        VideoContent expected = new VideoContent(
                "Josh M", "This is the 30 second thing to do after every TikTok post…展开", "");
        VideoContent expanded = new VideoContent(
                "Josh M",
                "This is the 30 second thing to do after every TikTok post to get your content moving",
                "");

        assertTrue(VideoContent.matchesFingerprint(expected.fingerprint(), expanded));
    }

    @Test
    public void sameAuthorWithDifferentContentIsNotTheSameVideo() {
        VideoContent expected = new VideoContent(
                "Creator", "A detailed guide to Android accessibility contracts", "");
        VideoContent different = new VideoContent(
                "Creator", "A completely different cooking tutorial for beginners", "");

        assertFalse(VideoContent.matchesFingerprint(expected.fingerprint(), different));
    }

    @Test
    public void sameDescriptionFromDifferentAuthorIsNotTheSameVideo() {
        VideoContent expected = new VideoContent(
                "Creator A", "A detailed guide to Android accessibility contracts", "");
        VideoContent different = new VideoContent(
                "Creator B", "A detailed guide to Android accessibility contracts", "");

        assertFalse(VideoContent.matchesFingerprint(expected.fingerprint(), different));
    }

    @Test
    public void missingObservedDescriptionCannotPassOnAuthorAlone() {
        VideoContent expected = new VideoContent(
                "Creator", "A detailed guide to Android accessibility contracts", "");
        VideoContent incomplete = new VideoContent("Creator", "", "");

        assertFalse(VideoContent.matchesFingerprint(expected.fingerprint(), incomplete));
    }
}
