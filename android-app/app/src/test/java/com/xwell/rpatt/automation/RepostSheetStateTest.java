package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;

public final class RepostSheetStateTest {
    @Test
    public void classifiesAvailableRepostEntry() {
        UiNode create = node("转发", 27, 1776, 213, 2028, "0/5/0");

        RepostSheetState.Result result = RepostSheetState.analyze(snapshot(create));

        assertEquals(RepostSheetState.State.AVAILABLE, result.state);
        assertSame(create, result.createEntry.node);
    }

    @Test
    public void classifiesChineseDeleteRepostAsAlreadyReposted() {
        UiNode remove = node("删除转发", 27, 1776, 213, 2028, "0/5/0");

        RepostSheetState.Result result = RepostSheetState.analyze(snapshot(remove));

        assertEquals(RepostSheetState.State.ALREADY_REPOSTED, result.state);
        assertSame(remove, result.removeEntry.node);
    }

    @Test
    public void conflictingCreateAndRemoveEntriesAreUnknown() {
        UiNode create = node("Repost", 27, 1776, 213, 2028, "0/5/0");
        UiNode remove = node("Undo repost", 230, 1776, 416, 2028, "0/5/1");

        assertEquals(RepostSheetState.State.UNKNOWN,
                RepostSheetState.analyze(snapshot(create, remove)).state);
    }

    @Test
    public void ordinaryShareTargetsCannotImpersonateRepostState() {
        UiNode whatsapp = node("WhatsApp", 230, 1776, 416, 2028, "0/5/1");

        assertEquals(RepostSheetState.State.UNKNOWN,
                RepostSheetState.analyze(snapshot(whatsapp)).state);
    }

    private static UiSnapshot snapshot(UiNode... nodes) {
        return new UiSnapshot(1L, PageAnalyzer.TIKTOK_PACKAGE, 1080, 2249,
                Arrays.asList(nodes));
    }

    private static UiNode node(String text, int left, int top, int right, int bottom,
                               String path) {
        Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return new UiNode("", text, "", "android.widget.Button",
                PageAnalyzer.TIKTOK_PACKAGE, false, true, true, bounds, 6268, path);
    }
}
