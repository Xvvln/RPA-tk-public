package com.xwell.rpatt.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class PreflightReportTest {
    @Test
    public void allGreenPasses() throws Exception {
        PreflightReport report = PreflightReport.evaluateEnvironment(
                true, true, false, true, true, true, true, true);
        assertEquals(PreflightReport.Status.PASS, report.status);
        assertTrue(report.passed());
        assertEquals(null, report.firstHardFailure());
        JSONObject json = report.toJson();
        assertEquals("pass", json.getString("status"));
        assertEquals(0, json.getJSONArray("hard_failures").length());
        assertEquals(7, json.getJSONArray("checks").length());
    }

    @Test
    public void hardFailureBlocksRun() throws Exception {
        PreflightReport report = PreflightReport.evaluateEnvironment(
                false, true, false, true, true, true, true, true);
        assertEquals(PreflightReport.Status.FAIL, report.status);
        assertFalse(report.passed());
        PreflightReport.Check hard = report.firstHardFailure();
        assertNotNull(hard);
        assertEquals("P01", hard.id);
        assertEquals("accessibility_disabled", hard.code);
        assertEquals(1, report.toJson().getJSONArray("hard_failures").length());
    }

    @Test
    public void softOverlayWarningStillPasses() throws Exception {
        PreflightReport report = PreflightReport.evaluateEnvironment(
                true, true, false, true, true, true, false, true);
        assertEquals(PreflightReport.Status.PASS_WITH_WARNINGS, report.status);
        assertTrue(report.passed());
        assertEquals(null, report.firstHardFailure());
        assertEquals(1, report.toJson().getJSONArray("warnings").length());
        assertEquals("overlay_unavailable",
                report.toJson().getJSONArray("warnings").getJSONObject(0).getString("code"));
    }

    @Test
    public void networkOptionalWhenNotRequired() {
        PreflightReport report = PreflightReport.evaluateEnvironment(
                true, true, false, false, false, true, true, true);
        assertEquals(PreflightReport.Status.PASS, report.status);
        assertTrue(report.passed());
    }

    @Test
    public void lockedDeviceIsHardFail() {
        PreflightReport report = PreflightReport.evaluateEnvironment(
                true, true, true, true, true, true, true, true);
        assertEquals(PreflightReport.Status.FAIL, report.status);
        assertEquals("device_locked", report.firstHardFailure().code);
    }
}
