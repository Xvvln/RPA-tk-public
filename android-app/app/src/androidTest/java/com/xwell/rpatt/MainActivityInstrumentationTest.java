package com.xwell.rpatt;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Device UI checks deliberately avoid starting a TikTok workflow or any social write. */
@RunWith(AndroidJUnit4.class)
public final class MainActivityInstrumentationTest {
    private static final long TIMEOUT_MS = 5_000L;
    private UiDevice device;
    private Context targetContext;

    @Before
    public void launchFreshDashboard() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(targetContext, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        targetContext.startActivity(intent);
        assertTrue(device.wait(Until.hasObject(By.pkg(targetContext.getPackageName()).depth(0)),
                TIMEOUT_MS));
    }

    @Test
    public void dashboardColdLaunchShowsCoreControls() {
        assertTrue(device.wait(Until.hasObject(By.text("TK养号助手")), TIMEOUT_MS));
        assertTrue(device.hasObject(By.text("开始任务")));
        assertTrue(device.hasObject(By.text("调整方案")));
        assertTrue(device.hasObject(By.text("最近运行")));
    }

    @Test
    public void applicationSettingsRoundTripKeepsDashboardReachable() {
        UiObject2 settings = device.wait(
                Until.findObject(By.desc("打开应用设置")), TIMEOUT_MS);
        assertNotNull(settings);
        settings.click();
        assertTrue(device.wait(Until.hasObject(By.text("应用设置")), TIMEOUT_MS));
        device.pressBack();
        assertTrue(device.wait(Until.hasObject(By.text("开始任务")), TIMEOUT_MS));
    }
}
