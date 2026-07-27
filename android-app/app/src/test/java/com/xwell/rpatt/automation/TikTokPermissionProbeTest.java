package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xwell.rpatt.engine.PreflightReport;

import org.json.JSONObject;
import org.junit.Test;

public final class TikTokPermissionProbeTest {
    @Test
    public void warmupFriendlyWhenDeniedPolicyItemsClosed() {
        // photos=true would mismatch DENIED policy for photos
        TikTokPermissionProbe.Report report = TikTokPermissionProbe.fromFlags(
                true, "46.1.3",
                false, false, false, false, false, true);
        assertTrue(report.warmupFriendly());
        assertEquals(0, report.mismatchCount());
        assertTrue(report.summaryLine().contains("符合养号策略"));
    }

    @Test
    public void warnsWhenContactsGranted() throws Exception {
        TikTokPermissionProbe.Report report = TikTokPermissionProbe.fromFlags(
                true, "46.1.3",
                true, false, false, false, false, false);
        assertFalse(report.warmupFriendly());
        assertEquals(1, report.mismatchCount());
        assertTrue(report.summaryLine().contains("建议关闭"));
        JSONObject json = report.toJson();
        assertEquals(1, json.getInt("mismatch_count"));
        assertFalse(json.getBoolean("warmup_friendly"));
    }

    @Test
    public void policySpecsCoverDangerousApkPermissions() {
        assertEquals("46.1.3", WarmupPermissionPolicy.BASELINE_VERSION_NAME);
        assertTrue(WarmupPermissionPolicy.specs().size() >= 5);
        assertEquals(WarmupPermissionPolicy.Desired.DENIED,
                WarmupPermissionPolicy.byId("contacts").desired);
        assertEquals(WarmupPermissionPolicy.Desired.DENIED,
                WarmupPermissionPolicy.byId("camera").desired);
    }

    @Test
    public void preflightIncludesP12AndWarnsOnMismatch() throws Exception {
        TikTokPermissionProbe.Report perms = TikTokPermissionProbe.fromFlags(
                true, "46.1.3",
                true, false, false, false, false, null);
        PreflightReport preflight = PreflightReport.evaluateEnvironment(
                true, true, false, true, true, true, true, true, perms);
        assertEquals(PreflightReport.Status.PASS_WITH_WARNINGS, preflight.status);
        assertTrue(preflight.passed());
        boolean sawContacts = false;
        for (PreflightReport.Check check : preflight.checks) {
            if ("P12_contacts".equals(check.id)) {
                sawContacts = true;
                assertFalse(check.ok);
                assertEquals("tiktok_permission_granted_contacts", check.code);
            }
        }
        assertTrue(sawContacts);
        assertTrue(preflight.toJson().getJSONArray("warnings").length() >= 1);
    }

    @Test
    public void preflightFriendlyTikTokPermissionsStayPass() {
        TikTokPermissionProbe.Report perms = TikTokPermissionProbe.fromFlags(
                true, "46.1.3",
                false, false, false, false, false, null);
        PreflightReport preflight = PreflightReport.evaluateEnvironment(
                true, true, false, true, true, true, true, true, perms);
        assertEquals(PreflightReport.Status.PASS, preflight.status);
    }
}
