package com.xwell.rpatt.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ActionLedgerTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void onlyVerifiedTransactionsCanCommitAndEnterSummary() throws Exception {
        Fixture fixture = fixture("run-a");
        ActionLedger.BeginResult begin = fixture.ledger.begin(
                "like", "author\ndescription", "", new JSONObject().put("control", "like"));

        begin.transaction.attempted(new JSONObject().put("tap", true));
        begin.transaction.verified(new JSONObject().put("selected", true));
        begin.transaction.committed(new JSONObject().put("selected", true));

        assertEquals(1, fixture.ledger.committedCount("like"));
        assertEquals(1, fixture.ledger.committedTotal());
        assertTrue(fixture.ledger.hasPartialVideoActions());
        fixture.ledger.markVideoCommitted("author\ndescription");
        assertFalse(fixture.ledger.hasPartialVideoActions());
    }

    @Test
    public void commitWithoutVerificationIsRejected() throws Exception {
        Fixture fixture = fixture("run-b");
        ActionLedger.Transaction transaction = fixture.ledger.begin(
                "favorite", "author\ndescription", "", new JSONObject()).transaction;
        transaction.attempted(new JSONObject());

        assertThrows(IOException.class, () -> transaction.committed(new JSONObject()));
        assertEquals(0, fixture.ledger.committedTotal());
    }

    @Test
    public void committedActionIsDeduplicatedAcrossRuns() throws Exception {
        Fixture fixture = fixture("run-c1");
        ActionLedger.Transaction transaction = fixture.ledger.begin(
                "comment", "author\ndescription", "hello", new JSONObject()).transaction;
        transaction.attempted(new JSONObject());
        transaction.verified(new JSONObject());
        transaction.committed(new JSONObject());

        ActionLedger nextRun = new ActionLedger(temporary.newFile("run-c2.jsonl"),
                fixture.global, "run-c2");
        ActionLedger.BeginResult duplicate = nextRun.begin(
                "comment", "author\ndescription", "hello", new JSONObject());

        assertEquals(ActionLedger.BeginDisposition.DUPLICATE_COMMITTED,
                duplicate.disposition);
        assertEquals(ActionLedger.State.COMMITTED, duplicate.priorState);
    }

    @Test
    public void attemptedButUnverifiedActionCannotBeRetried() throws Exception {
        Fixture fixture = fixture("run-d1");
        ActionLedger.Transaction transaction = fixture.ledger.begin(
                "follow", "author\ndescription", "", new JSONObject()).transaction;
        transaction.attempted(new JSONObject());
        transaction.unknown("verification_failed", new JSONObject());

        ActionLedger nextRun = new ActionLedger(temporary.newFile("run-d2.jsonl"),
                fixture.global, "run-d2");
        ActionLedger.BeginResult duplicate = nextRun.begin(
                "follow", "author\ndescription", "", new JSONObject());

        assertEquals(ActionLedger.BeginDisposition.DUPLICATE_UNCERTAIN,
                duplicate.disposition);
        assertEquals(ActionLedger.State.UNKNOWN, duplicate.priorState);
    }

    @Test
    public void globalIndexContainsHashesButNoRawContent() throws Exception {
        Fixture fixture = fixture("run-e");
        fixture.ledger.begin("comment", "private author\nprivate description",
                "private comment", new JSONObject());

        String globalText = new String(Files.readAllBytes(
                fixture.global.toPath()), StandardCharsets.UTF_8);
        assertFalse(globalText.contains("private author"));
        assertFalse(globalText.contains("private description"));
        assertFalse(globalText.contains("private comment"));
        assertTrue(globalText.contains("idempotency_key"));
        assertTrue(globalText.contains("target_hash"));
    }

    private Fixture fixture(String runId) throws Exception {
        File run = temporary.newFile(runId + ".jsonl");
        File global = temporary.newFile(runId + "-global.jsonl");
        return new Fixture(new ActionLedger(run, global, runId), global);
    }

    private static final class Fixture {
        final ActionLedger ledger;
        final File global;

        Fixture(ActionLedger ledger, File global) {
            this.ledger = ledger;
            this.global = global;
        }
    }
}
