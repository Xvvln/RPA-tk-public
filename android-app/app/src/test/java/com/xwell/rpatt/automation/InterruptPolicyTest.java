package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class InterruptPolicyTest {
    @Test
    public void softDismissAndBlacklistAreDisjoint() {
        assertTrue(InterruptPolicy.softAndBlacklistDisjoint());
    }

    @Test
    public void filterSoftLabelsDropsPrimaryAccept() {
        String[] filtered = InterruptPolicy.filterSoftLabels(
                "以后再说", "Allow", "Continue", "Not now", "同意", "  ", null);
        assertEquals(2, filtered.length);
        assertEquals("以后再说", filtered[0]);
        assertEquals("Not now", filtered[1]);
        for (String label : filtered) {
            assertFalse(InterruptPolicy.isPrimaryAcceptLabel(label));
            assertTrue(InterruptPolicy.isSoftDismissLabel(label));
        }
    }

    @Test
    public void blockersNeverProduceRecoveryCode() {
        PageState state = new PageState(
                PageState.Page.FEED,
                "video",
                Collections.singletonList("permission_required"),
                Collections.singletonList("generic_dismiss_popup"));
        assertNull(InterruptPolicy.primaryRecoverableCode(state, PageState.Page.FEED));
        assertFalse(InterruptPolicy.hasRecoverableInterruption(state, PageState.Page.FEED));
    }

    @Test
    public void pureUnknownOnFeedExpectedPressesBackFirst() {
        PageState state = new PageState(
                PageState.Page.UNKNOWN,
                "none",
                Collections.emptyList(),
                Collections.emptyList(),
                0d,
                Collections.singletonList("classification:insufficient_or_conflicting_evidence"));
        assertEquals("anomaly_press_back",
                InterruptPolicy.primaryRecoverableCode(state, PageState.Page.FEED));
        assertEquals("press_back",
                InterruptPolicy.recoveryActionFor("anomaly_press_back"));
        assertEquals("press_back_then_home",
                InterruptPolicy.recoveryActionFor("search_results"));
    }

    @Test
    public void partialCommentsOpenOnUnknownIsRecoverableForFeed() {
        PageState state = new PageState(
                PageState.Page.UNKNOWN,
                "none",
                Collections.emptyList(),
                Collections.singletonList("comments_open"),
                0d,
                Collections.singletonList("comments:partial_composer_recoverable"));
        assertEquals("comments_open",
                InterruptPolicy.primaryRecoverableCode(state, PageState.Page.FEED));
        assertEquals("press_back", InterruptPolicy.recoveryActionFor("comments_open"));
    }

    @Test
    public void firstTimeRepostGuidePriorityAndAction() {
        PageState state = state(PageState.Page.FEED, "first_time_repost_guide", "got_it_popup");
        assertEquals("first_time_repost_guide",
                InterruptPolicy.primaryRecoverableCode(state, PageState.Page.FEED));
        assertEquals("dismiss_repost_guide",
                InterruptPolicy.recoveryActionFor("first_time_repost_guide"));
    }

    @Test
    public void systemPermissionDenyIsRecoverableAndRejectNeverAllow() {
        assertTrue(InterruptPolicy.isSystemPermissionDenyLabel("拒绝"));
        assertTrue(InterruptPolicy.isSystemPermissionDenyLabel("Don't allow"));
        assertTrue(InterruptPolicy.isSoftDismissLabel("拒绝"));
        assertTrue(InterruptPolicy.isPrimaryAcceptLabel("允许"));
        assertFalse(InterruptPolicy.isSystemPermissionDenyLabel("允许"));

        PageState state = state(PageState.Page.UNKNOWN,
                "system_permission_prompt", "contacts_sync_prompt");
        assertEquals("system_permission_prompt",
                InterruptPolicy.primaryRecoverableCode(state, PageState.Page.FEED));
        assertEquals("deny_system_permission",
                InterruptPolicy.recoveryActionFor("system_permission_prompt"));
    }

    @Test
    public void recoveryPriorityMatchesEngineOrder() {
        PageState contacts = state(PageState.Page.FEED,
                "contacts_sync_prompt", "generic_dismiss_popup");
        assertEquals("contacts_sync_prompt",
                InterruptPolicy.primaryRecoverableCode(contacts, PageState.Page.FEED));
        assertEquals("deny_contacts",
                InterruptPolicy.recoveryActionFor("contacts_sync_prompt"));

        PageState sheet = state(PageState.Page.FEED, "video_options_sheet", "got_it_popup");
        assertEquals("video_options_sheet",
                InterruptPolicy.primaryRecoverableCode(sheet, PageState.Page.FEED));
        assertEquals("press_back", InterruptPolicy.recoveryActionFor("video_options_sheet"));

        PageState comments = new PageState(
                PageState.Page.COMMENTS,
                "none",
                Collections.emptyList(),
                Collections.singletonList("comments_open"));
        assertEquals("comments_open",
                InterruptPolicy.primaryRecoverableCode(comments, PageState.Page.FEED));

        PageState inbox = new PageState(
                PageState.Page.INBOX,
                "none",
                Collections.emptyList(),
                Collections.singletonList("inbox_page"));
        assertEquals("inbox", InterruptPolicy.primaryRecoverableCode(inbox, PageState.Page.FEED));
        assertEquals("press_back_then_home", InterruptPolicy.recoveryActionFor("inbox"));
    }

    @Test
    public void suggestionOnlyWhenFeedExpected() {
        PageState state = state(PageState.Page.FEED, "suggestion_page");
        assertEquals("suggestion_page",
                InterruptPolicy.primaryRecoverableCode(state, PageState.Page.FEED));
        assertNull(InterruptPolicy.primaryRecoverableCode(state, PageState.Page.COMMENTS));
    }

    private static PageState state(PageState.Page page, String... interruptions) {
        return new PageState(page, "video", Collections.emptyList(), Arrays.asList(interruptions));
    }
}
