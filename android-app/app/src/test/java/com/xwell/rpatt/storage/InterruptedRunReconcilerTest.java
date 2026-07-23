package com.xwell.rpatt.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class InterruptedRunReconcilerTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void reconstructsCommittedAndUncertainActionsFromDurableFiles() throws Exception {
        File run = createRun("20260723-121349-038");
        String target = InterruptedRunReconciler.sha256("author\ndescription\nmusic");
        write(new File(run, "videos.jsonl"),
                new JSONObject().put("author", "author").put("description", "description")
                        .put("music", "music").toString() + "\n");
        write(new File(run, "actions.jsonl"),
                action("tx-like", "like", "prepared", target)
                        + action("tx-like", "like", "attempted", target)
                        + action("tx-like", "like", "verified", target)
                        + action("tx-like", "like", "committed", target)
                        + action("tx-comment", "comment", "prepared", "other")
                        + action("tx-comment", "comment", "attempted", "other")
                        + "{torn");

        List<InterruptedRunReconciler.Result> results =
                InterruptedRunReconciler.reconcile(temporary.getRoot(), false, 4_000L);

        assertEquals(1, results.size());
        JSONObject summary = results.get(0).summary;
        assertEquals(1, summary.getInt("videos_seen"));
        assertEquals(1, summary.getInt("likes"));
        assertEquals(1, summary.getInt("external_actions_committed"));
        assertEquals(1, summary.getInt("unfinalized_action_transactions"));
        assertEquals(1, summary.getInt("uncertain_action_transactions"));
        assertFalse(summary.getBoolean("partial_video_actions"));
        assertEquals(InterruptedRunReconciler.STOP_REASON,
                summary.getString("stop_reason"));
        assertTrue(summary.getBoolean("requires_human"));
        assertEquals(2_000L, summary.getLong("elapsed_ms"));
    }

    @Test
    public void activeSessionProtectsNewestIncompleteRunAndRecoversOlderOne() throws Exception {
        File older = createRun("20260723-100000-000");
        File active = createRun("20260723-110000-000");

        List<InterruptedRunReconciler.Result> results =
                InterruptedRunReconciler.reconcile(temporary.getRoot(), true, 4_000L);

        assertEquals(1, results.size());
        assertEquals(older.getName(), results.get(0).directory.getName());
        assertTrue(new File(older, "summary.json").isFile());
        assertFalse(new File(active, "summary.json").isFile());
    }

    @Test
    public void completedAndAlreadyRecoveredRunsAreIdempotent() throws Exception {
        File run = createRun("20260723-120000-000");
        write(new File(run, "summary.json"), "{}\n");

        assertTrue(InterruptedRunReconciler.reconcile(
                temporary.getRoot(), false, 4_000L).isEmpty());
        assertEquals("{}\n", new String(Files.readAllBytes(
                new File(run, "summary.json").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void committedActionWithoutMatchingVideoIsMarkedPartial() throws Exception {
        File run = createRun("20260723-130000-000");
        write(new File(run, "actions.jsonl"),
                action("tx-share", "share", "committed", "missing-target"));

        JSONObject summary = InterruptedRunReconciler.reconcile(
                temporary.getRoot(), false, 4_000L).get(0).summary;

        assertTrue(summary.getBoolean("partial_video_actions"));
        assertEquals(1, summary.getInt("shares"));
    }

    private File createRun(String name) throws Exception {
        File runs = new File(temporary.getRoot(), "runs");
        assertTrue(runs.isDirectory() || runs.mkdirs());
        File run = new File(runs, name);
        assertTrue(run.mkdir());
        write(new File(run, "config.json"), "{\"schema_version\":3}\n");
        write(new File(run, "events.jsonl"),
                "{\"at\":1000,\"type\":\"start\"}\n{\"at\":3000,\"type\":\"last\"}\n");
        return run;
    }

    private static String action(
            String transaction, String action, String state, String target) throws Exception {
        return new JSONObject()
                .put("transaction_id", transaction)
                .put("action", action)
                .put("state", state)
                .put("target_hash", target)
                .toString() + "\n";
    }

    private static void write(File file, String value) throws Exception {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
}
