package com.xwell.rpatt.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.widget.Toast;

import com.xwell.rpatt.MainActivity;
import com.xwell.rpatt.SettingsActivity;
import com.xwell.rpatt.automation.AccessibilityGateway;
import com.xwell.rpatt.automation.PageAnalyzer;
import com.xwell.rpatt.automation.PageState;
import com.xwell.rpatt.automation.WarmupAccessibilityService;
import com.xwell.rpatt.config.ConfigStore;
import com.xwell.rpatt.engine.RunSnapshot;
import com.xwell.rpatt.overlay.FloatingOverlayController;
import com.xwell.rpatt.overlay.OverlayActions;
import com.xwell.rpatt.runtime.WarmupSessionController;
import com.xwell.rpatt.storage.InterruptedRunReconciler;

import java.util.List;

/** Android composition root: wires the independent runtime, overlay and notification layers. */
public final class WarmupForegroundService extends Service
        implements WarmupSessionController.Listener, OverlayActions {
    public static final String ACTION_SHOW = "com.xwell.rpatt.SHOW";
    public static final String ACTION_START = "com.xwell.rpatt.START";
    public static final String ACTION_PAUSE = "com.xwell.rpatt.PAUSE";
    public static final String ACTION_PAUSE_FOR_CONTROL = "com.xwell.rpatt.PAUSE_FOR_CONTROL";
    public static final String ACTION_OPEN_CONTROL = "com.xwell.rpatt.OPEN_CONTROL";
    public static final String ACTION_RETURN_TO_TASK = "com.xwell.rpatt.RETURN_TO_TASK";
    public static final String ACTION_STOP = "com.xwell.rpatt.STOP";
    public static final String ACTION_HIDE = "com.xwell.rpatt.HIDE";
    private static final String CHANNEL = "warmup_session";
    private static final String ATTENTION_CHANNEL = "warmup_attention_v1";
    private static final int NOTIFICATION_ID = 41;
    private static final int ATTENTION_NOTIFICATION_ID = 42;

    private FloatingOverlayController overlay;
    private WarmupSessionController session;
    private RunSnapshot.Status lastStatus = RunSnapshot.Status.IDLE;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private InterruptedRunReconciler.Result recoveredInterruption;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            List<InterruptedRunReconciler.Result> recovered =
                    InterruptedRunReconciler.reconcile(
                            getFilesDir(), WarmupSessionController.hasActiveSession(),
                            System.currentTimeMillis());
            if (!recovered.isEmpty()) recoveredInterruption = recovered.get(0);
        } catch (Exception ignored) {}
        session = new WarmupSessionController(this, this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (action == null) action = ACTION_SHOW;
        startForeground(NOTIFICATION_ID, notification(session.current()));
        if (recoveredInterruption != null) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(ATTENTION_NOTIFICATION_ID,
                    interruptedNotification(recoveredInterruption));
            recoveredInterruption = null;
        }
        if (ACTION_HIDE.equals(action)) {
            hideOverlay();
            if (!session.isRunning()) stopSelf();
            return START_NOT_STICKY;
        }
        showOverlay();
        if (ACTION_START.equals(action)) {
            session.start();
        } else if (ACTION_PAUSE.equals(action)) {
            session.togglePause();
        } else if (ACTION_PAUSE_FOR_CONTROL.equals(action)) {
            session.pauseIfRunning();
        } else if (ACTION_OPEN_CONTROL.equals(action)) {
            requestControlOpen();
        } else if (ACTION_RETURN_TO_TASK.equals(action)) {
            returnToTask();
        } else if (ACTION_STOP.equals(action)) {
            session.stop();
        }
        return START_NOT_STICKY;
    }

    @Override public void onSessionChanged(RunSnapshot snapshot) {
        if (overlay != null) overlay.render(RunUiStateMapper.toOverlayState(snapshot));
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification(snapshot));
        boolean newlyBlocked = snapshot.status == RunSnapshot.Status.BLOCKED
                && lastStatus != RunSnapshot.Status.BLOCKED;
        if (newlyBlocked) {
            manager.notify(ATTENTION_NOTIFICATION_ID, attentionNotification(snapshot));
            vibrateForBlock();
        } else if (snapshot.status != RunSnapshot.Status.BLOCKED) {
            manager.cancel(ATTENTION_NOTIFICATION_ID);
        }
        lastStatus = snapshot.status;
    }

    @Override public void onDestroy() {
        if (session != null) session.close();
        hideOverlay();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void showOverlay() {
        if (!Settings.canDrawOverlays(this)) return;
        if (overlay == null) overlay = new FloatingOverlayController(this, this);
        overlay.show(ConfigStore.load(this).startCollapsed);
        overlay.render(RunUiStateMapper.toOverlayState(session.current()));
    }

    private void hideOverlay() {
        if (overlay != null) overlay.hide();
        overlay = null;
    }

    @Override public void onStartRequested() { session.start(); }
    @Override public void onPauseResumeRequested() {
        if (session.current().status == RunSnapshot.Status.PAUSED) returnToTask();
        else session.pauseIfRunning();
    }
    @Override public void onStopRequested() { session.stop(); }
    @Override public void onChooseProfileRequested() { openActivity(SettingsActivity.class); }
    @Override public void onOpenAppRequested() {
        requestControlOpen();
    }
    @Override public void onCloseRequested() {
        hideOverlay();
        if (!session.isRunning()) stopSelf();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "TK养号助手本地任务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示本机浏览任务状态与暂停/停止入口");
        NotificationChannel attention = new NotificationChannel(ATTENTION_CHANNEL,
                "任务异常提醒", NotificationManager.IMPORTANCE_HIGH);
        attention.setDescription("任务停止且需要人工处理时显示醒目提醒");
        attention.enableVibration(true);
        attention.setVibrationPattern(new long[] {0, 250, 150, 350});
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
        manager.createNotificationChannel(attention);
    }

    private Notification notification(RunSnapshot snapshot) {
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("TK养号助手 · " + snapshot.status.name())
                .setContentText(snapshot.phase + " · " + snapshot.step + " · " + snapshot.videosSeen + "/" + snapshot.videoLimit)
                .setContentIntent(open)
                .setOngoing(snapshot.status == RunSnapshot.Status.RUNNING || snapshot.status == RunSnapshot.Status.PAUSED)
                .setColor(Color.rgb(255, 44, 85));
        if (snapshot.status == RunSnapshot.Status.RUNNING || snapshot.status == RunSnapshot.Status.PAUSED) {
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    snapshot.status == RunSnapshot.Status.PAUSED ? "继续" : "暂停", command(ACTION_PAUSE, 2)).build());
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "停止", command(ACTION_STOP, 3)).build());
        }
        return builder.build();
    }

    private Notification attentionNotification(RunSnapshot snapshot) {
        PendingIntent open = PendingIntent.getActivity(this, 4,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String reason = snapshot.message == null || snapshot.message.trim().isEmpty()
                ? snapshot.step : snapshot.message;
        return new Notification.Builder(this, ATTENTION_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("任务已停止，需要人工处理")
                .setContentText(reason)
                .setStyle(new Notification.BigTextStyle().bigText(reason + "\n点击查看运行日志和处理入口。"))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .setColor(Color.rgb(235, 74, 86))
                .build();
    }

    private Notification interruptedNotification(InterruptedRunReconciler.Result result) {
        PendingIntent open = PendingIntent.getActivity(this, 5,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = result.summary.optInt("videos_seen") + " 条视频已保留，"
                + result.summary.optInt("external_actions_committed") + " 次已确认操作不会重试";
        return new Notification.Builder(this, ATTENTION_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("上次任务异常中断")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(
                        text + "\n运行记录已归档，请查看日志后再开始新任务。"))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .setColor(Color.rgb(235, 74, 86))
                .build();
    }

    private void vibrateForBlock() {
        if (!ConfigStore.load(this).vibrateOnBlock) return;
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[] {0, 250, 150, 350}, -1));
        }
    }

    private void openActivity(Class<?> activityClass) {
        openActivity(activityClass, false);
    }

    private void openActivity(Class<?> activityClass, boolean safeControlReady) {
        Intent intent = new Intent(this, activityClass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (safeControlReady) intent.putExtra(MainActivity.EXTRA_SAFE_CONTROL_READY, true);
        startActivity(intent);
    }

    private void returnToTask() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(
                com.xwell.rpatt.automation.PageAnalyzer.TIKTOK_PACKAGE);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launch);
        waitForStableFeedThenResume(0, 0);
    }

    private void requestControlOpen() {
        if (session.current().status != RunSnapshot.Status.RUNNING) {
            openActivity(MainActivity.class, true);
            return;
        }
        session.pauseIfRunning();
        waitForCheckpointThenOpen(0);
    }

    private void waitForCheckpointThenOpen(int attempt) {
        if (session == null) return;
        RunSnapshot.Status status = session.current().status;
        if (status == RunSnapshot.Status.PAUSED
                || status == RunSnapshot.Status.BLOCKED
                || status == RunSnapshot.Status.FINISHED
                || status == RunSnapshot.Status.IDLE) {
            openActivity(MainActivity.class, true);
            return;
        }
        if (attempt >= 900) {
            Toast.makeText(this,
                    "当前视频尚未安全收尾，任务继续运行", Toast.LENGTH_LONG).show();
            return;
        }
        mainHandler.postDelayed(() -> waitForCheckpointThenOpen(attempt + 1), 100L);
    }

    private void waitForStableFeedThenResume(int attempt, int consecutive) {
        if (session == null || session.current().status != RunSnapshot.Status.PAUSED) return;
        int stable = 0;
        try {
            if (WarmupAccessibilityService.isConnected()) {
                AccessibilityGateway gateway = AccessibilityGateway.require();
                PageState state = PageAnalyzer.analyze(
                        gateway.snapshot(), true);
                if (state.page == PageState.Page.FEED && state.blockers.isEmpty()) {
                    stable = consecutive + 1;
                }
            }
        } catch (Exception ignored) {}
        if (stable >= 2) {
            session.resumeIfPaused();
            return;
        }
        if (attempt >= 80) {
            Toast.makeText(this,
                    "TikTok 页面尚未稳定，任务保持暂停", Toast.LENGTH_LONG).show();
            return;
        }
        final int nextStable = stable;
        mainHandler.postDelayed(
                () -> waitForStableFeedThenResume(attempt + 1, nextStable), 150L);
    }

    private PendingIntent command(String action, int requestCode) {
        Intent intent = new Intent(this, WarmupForegroundService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
