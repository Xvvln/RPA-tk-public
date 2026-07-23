package com.xwell.rpatt.overlay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.xwell.rpatt.R;
import com.xwell.rpatt.config.ConfigStore;
import com.xwell.rpatt.ui.RpaPalette;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.transition.platform.MaterialSharedAxis;

import java.util.Locale;

/** Narrow, task-only floating controller. It has no dependency on the automation engine. */
public final class FloatingOverlayController {
    private final Context context;
    private final OverlayActions actions;
    private final WindowManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager.LayoutParams params;
    private WindowManager.LayoutParams alertParams;
    private LinearLayout root;
    private MaterialCardView alertBanner;
    private MaterialCardView panel;
    private DragBubble bubble;
    private CircularProgressIndicator progressRing;
    private TextView progressText;
    private TextView blockedMark;
    private TextView phase;
    private MaterialButton primary;
    private TextView primaryLabel;
    private MaterialButton stop;
    private SwipePageHost pageHost;
    private LinearLayout runDots;
    private LinearLayout actionDots;
    private TextView profileValue;
    private int pageIndex;
    private OverlayState lastState;

    public FloatingOverlayController(Context context, OverlayActions actions) {
        // A Service context does not always carry the Activity's Material theme. Wrap it so
        // overlay-created Material views resolve the exact same tokens as the main app.
        RpaPalette.apply(context);
        this.context = new ContextThemeWrapper(context, RpaPalette.themeResource(context));
        this.actions = actions;
        this.manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show(boolean collapsed) {
        if (root != null) return;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(4), dp(4), dp(4), dp(4));

        bubble = buildBubble();
        // Keep the collapsed controller compact while retaining a 48dp touch target.
        root.addView(bubble, new LinearLayout.LayoutParams(dp(48), dp(48)));

        panel = buildPanel();
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(dp(72), dp(250));
        panelParams.topMargin = dp(4);
        root.addView(panel, panelParams);

        bubble.setOnClickListener(view -> togglePanel());
        bubble.setOnLongClickListener(view -> {
            Toast.makeText(context, "已关闭悬浮控制", Toast.LENGTH_SHORT).show();
            actions.onCloseRequested();
            return true;
        });
        bubble.setOnTouchListener(new DragListener());
        panel.setVisibility(collapsed ? View.GONE : View.VISIBLE);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(10);
        params.y = dp(176);
        manager.addView(root, params);
    }

    public void hide() {
        main.post(() -> {
            if (root != null) manager.removeView(root);
            root = null;
            hideBlockedAlert();
        });
    }

    public void render(OverlayState state) {
        main.post(() -> {
            if (root == null) return;
            boolean newlyBlocked = state.status == OverlayState.Status.BLOCKED
                    && (lastState == null || lastState.status != OverlayState.Status.BLOCKED);
            lastState = state;

            int configuredLimit = state.videoLimit > 0
                    ? state.videoLimit
                    : ConfigStore.load(context).videoCount;
            int limit = Math.max(1, configuredLimit);
            float progress = Math.min(1f, state.videosSeen / (float) limit);
            progressRing.setIndicatorColor(
                    state.status == OverlayState.Status.BLOCKED ? RpaPalette.RED : RpaPalette.PINK);
            progressRing.setProgressCompat(Math.round(progress * 1000), true);
            progressText.setText(String.format(Locale.CHINA, "%d/%d", state.videosSeen, configuredLimit));
            phase.setText(shortPhase(state));

            boolean blocked = state.status == OverlayState.Status.BLOCKED;
            progressRing.setVisibility(blocked ? View.GONE : View.VISIBLE);
            progressText.setVisibility(blocked ? View.GONE : View.VISIBLE);
            blockedMark.setVisibility(blocked ? View.VISIBLE : View.GONE);
            phase.setTextColor(blocked ? RpaPalette.RED : RpaPalette.TEXT);
            phase.setTextSize(blocked ? 12 : 10);
            phase.setTypeface(Typeface.DEFAULT, blocked ? Typeface.BOLD : Typeface.NORMAL);
            panel.setStrokeColor(blocked ? RpaPalette.RED : RpaPalette.BORDER);
            panel.setStrokeWidth(dp(blocked ? 2 : 1));

            boolean active = state.active();
            // BLOCKED describes the previous run, not a permanently disabled controller.
            // Once the engine has stopped, the same button must become a fresh Start action.
            primary.setEnabled(active || state.canStart());
            primary.setIconResource(active
                    ? (state.paused() ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                    : android.R.drawable.ic_media_play);
            primary.setContentDescription(active ? (state.paused() ? "继续任务" : "暂停任务") : "开始任务");
            primaryLabel.setText(active ? (state.paused() ? "继续" : "暂停") : "开始");
            stop.setEnabled(active || state.status == OverlayState.Status.BLOCKED);
            stop.setAlpha(stop.isEnabled() ? 1f : 0.4f);
            if (newlyBlocked) {
                setPage(0);
                panel.setVisibility(View.VISIBLE);
                root.requestLayout();
                manager.updateViewLayout(root, params);
                showBlockedAlert(state.message);
            } else if (!blocked) {
                hideBlockedAlert();
            }
        });
    }

    private DragBubble buildBubble() {
        DragBubble value = new DragBubble(context);
        value.setCardBackgroundColor(Color.argb(246, 27, 29, 34));
        value.setRadius(dp(24));
        value.setStrokeWidth(dp(1));
        value.setStrokeColor(Color.argb(72, 255, 255, 255));
        value.setCardElevation(dp(8));
        value.setUseCompatPadding(false);

        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.tk_logo_mark);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setPadding(dp(4), dp(4), dp(4), dp(4));
        value.addView(logo, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));

        value.setContentDescription("TK养号助手悬浮控制，点击展开，长按关闭");
        return value;
    }

    private MaterialCardView buildPanel() {
        MaterialCardView value = new MaterialCardView(context);
        value.setCardBackgroundColor(Color.argb(245, 20, 21, 25));
        value.setRadius(dp(24));
        value.setStrokeWidth(dp(1));
        value.setStrokeColor(RpaPalette.BORDER);
        value.setCardElevation(dp(6));
        value.setUseCompatPadding(false);
        value.setContentPadding(dp(6), dp(6), dp(6), dp(6));

        pageHost = new SwipePageHost(context, direction -> setPage(pageIndex + direction));
        pageHost.addView(buildRunPage(), matchFrame());
        pageHost.addView(buildActionPage(), matchFrame());
        value.addView(pageHost, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setPage(0);
        return value;
    }

    private LinearLayout buildRunPage() {
        LinearLayout value = new SwipeSurface(context);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        value.setPadding(0, dp(3), 0, dp(2));
        value.setClickable(true);
        value.setOnTouchListener(new PageSwipeListener());

        FrameLayout progress = new FrameLayout(context);
        progressRing = new CircularProgressIndicator(context);
        progressRing.setIndeterminate(false);
        progressRing.setMax(1000);
        progressRing.setProgressCompat(0, false);
        progressRing.setIndicatorSize(dp(48));
        progressRing.setTrackThickness(dp(4));
        progressRing.setIndicatorColor(RpaPalette.PINK);
        progressRing.setTrackColor(Color.rgb(65, 39, 48));
        progress.addView(progressRing, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        progressText = label("0/0", 11, RpaPalette.TEXT);
        progressText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        progressText.setGravity(Gravity.CENTER);
        progress.addView(progressText, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        blockedMark = label("!", 29, Color.WHITE);
        blockedMark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        blockedMark.setGravity(Gravity.CENTER);
        blockedMark.setBackground(round(RpaPalette.RED, 24));
        blockedMark.setContentDescription("任务需要人工处理");
        blockedMark.setVisibility(View.GONE);
        progress.addView(blockedMark, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        value.addView(progress, progressParams);

        phase = label("等待", 10, RpaPalette.TEXT);
        phase.setGravity(Gravity.CENTER);
        phase.setMaxLines(1);
        LinearLayout.LayoutParams phaseParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18));
        phaseParams.topMargin = dp(2);
        value.addView(phase, phaseParams);

        View divider = new View(context);
        divider.setBackgroundColor(RpaPalette.BORDER);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(44), dp(1));
        dividerParams.topMargin = dp(3);
        dividerParams.bottomMargin = dp(4);
        value.addView(divider, dividerParams);

        primary = iconButton(android.R.drawable.ic_media_play, "开始任务", RpaPalette.PINK);
        primary.setOnClickListener(view -> {
            if (lastState != null && lastState.active()) actions.onPauseResumeRequested();
            else actions.onStartRequested();
        });
        value.addView(primary, square(42));
        primaryLabel = label("开始", 10, RpaPalette.TEXT_MUTED);
        primaryLabel.setGravity(Gravity.CENTER);
        value.addView(primaryLabel);

        stop = iconButton(android.R.drawable.ic_menu_close_clear_cancel, "停止任务", RpaPalette.PINK);
        stop.setOnClickListener(view -> actions.onStopRequested());
        LinearLayout.LayoutParams stopParams = square(42);
        stopParams.topMargin = dp(3);
        value.addView(stop, stopParams);
        TextView stopLabel = label("停止", 10, RpaPalette.TEXT_MUTED);
        stopLabel.setGravity(Gravity.CENTER);
        value.addView(stopLabel);

        runDots = buildPageDots();
        LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(
                dp(10), dp(18));
        dotsParams.topMargin = dp(3);
        value.addView(runDots, dotsParams);
        return value;
    }

    private LinearLayout buildActionPage() {
        LinearLayout value = new SwipeSurface(context);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        value.setPadding(0, dp(12), 0, dp(2));
        value.setClickable(true);
        value.setOnTouchListener(new PageSwipeListener());

        TextView profileLabel = label("方案", 10, RpaPalette.TEXT_MUTED);
        profileLabel.setGravity(Gravity.CENTER);
        value.addView(profileLabel);
        profileValue = label("轻量", 13, RpaPalette.TEXT);
        profileValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        profileValue.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28));
        value.addView(profileValue, profileParams);

        View divider = new View(context);
        divider.setBackgroundColor(RpaPalette.BORDER);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(44), dp(1));
        dividerParams.topMargin = dp(5);
        dividerParams.bottomMargin = dp(10);
        value.addView(divider, dividerParams);

        MaterialButton choose = compactAction("选择\n方案", "打开方案设置");
        choose.setOnClickListener(view -> {
            collapsePanel();
            actions.onChooseProfileRequested();
        });
        value.addView(choose, new LinearLayout.LayoutParams(dp(56), dp(48)));

        MaterialButton open = compactAction("打开\nApp", "打开 TK养号助手");
        open.setOnClickListener(view -> {
            collapsePanel();
            actions.onOpenAppRequested();
        });
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(dp(56), dp(48));
        openParams.topMargin = dp(10);
        value.addView(open, openParams);

        actionDots = buildPageDots();
        LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(
                dp(10), dp(18));
        dotsParams.topMargin = dp(12);
        value.addView(actionDots, dotsParams);
        return value;
    }

    private MaterialButton compactAction(String text, String description) {
        MaterialButton button = new MaterialButton(context);
        button.setText(text);
        button.setTextSize(10);
        button.setTextColor(RpaPalette.TEXT);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setContentDescription(description);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(30, 31, 37)));
        button.setStrokeColor(ColorStateList.valueOf(RpaPalette.BORDER));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(10));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private LinearLayout buildPageDots() {
        LinearLayout dots = new LinearLayout(context);
        dots.setOrientation(LinearLayout.VERTICAL);
        dots.setGravity(Gravity.CENTER);
        for (int index = 0; index < 2; index++) {
            FrameLayout targetView = new FrameLayout(context);
            final int target = index;
            targetView.setContentDescription(index == 0 ? "任务控制页" : "方案操作页");
            targetView.setOnClickListener(view -> setPage(target));
            View dot = new View(context);
            targetView.addView(dot, new FrameLayout.LayoutParams(dp(6), dp(6), Gravity.CENTER));
            dots.addView(targetView, new LinearLayout.LayoutParams(dp(10), dp(9)));
        }
        return dots;
    }

    private void setPage(int requested) {
        if (pageHost == null || pageHost.getChildCount() < 2) return;
        int target = Math.max(0, Math.min(1, requested));
        if (target != pageIndex && pageHost.isLaidOut()) {
            MaterialSharedAxis transition = new MaterialSharedAxis(
                    MaterialSharedAxis.Y, target > pageIndex);
            transition.setDuration(260L);
            TransitionManager.beginDelayedTransition(pageHost, transition);
        }
        pageIndex = target;
        pageHost.getChildAt(0).setVisibility(pageIndex == 0 ? View.VISIBLE : View.GONE);
        pageHost.getChildAt(1).setVisibility(pageIndex == 1 ? View.VISIBLE : View.GONE);
        updateDots(runDots);
        updateDots(actionDots);
        if (pageIndex == 1) refreshProfile();
    }

    private void updateDots(LinearLayout dots) {
        if (dots == null) return;
        for (int index = 0; index < dots.getChildCount(); index++) {
            FrameLayout target = (FrameLayout) dots.getChildAt(index);
            target.getChildAt(0).setBackground(round(index == pageIndex
                    ? RpaPalette.PINK : Color.rgb(92, 96, 107), 20));
        }
    }

    private void refreshProfile() {
        if (profileValue == null) return;
        switch (ConfigStore.load(context).profile) {
            case "normal": profileValue.setText("常规"); break;
            case "active": profileValue.setText("积极"); break;
            case "custom": profileValue.setText("自定义"); break;
            default: profileValue.setText("轻量");
        }
    }

    private void togglePanel() {
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) setPage(pageIndex);
    }

    private void collapsePanel() {
        if (panel == null || panel.getVisibility() == View.GONE) return;
        panel.setVisibility(View.GONE);
        root.requestLayout();
        manager.updateViewLayout(root, params);
    }

    private MaterialButton iconButton(int icon, String description, int tint) {
        MaterialButton button = new MaterialButton(context);
        button.setText("");
        button.setIconResource(icon);
        button.setIconTint(ColorStateList.valueOf(tint));
        button.setIconSize(dp(18));
        button.setIconPadding(0);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setContentDescription(description);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(30, 31, 37)));
        button.setStrokeColor(ColorStateList.valueOf(RpaPalette.BORDER));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(21));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private TextView label(String text, int size, int color) {
        MaterialTextView view = new MaterialTextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private String shortPhase(OverlayState state) {
        if (state.status == OverlayState.Status.BLOCKED) return "需人工处理";
        if (state.status == OverlayState.Status.PAUSED) return "已暂停";
        if (state.status == OverlayState.Status.FINISHED) return "已结束";
        if (state.status == OverlayState.Status.IDLE) return "等待";
        return "运行中";
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable outlined(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = round(fill, radiusDp);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    /**
     * Show an app-owned warning above TikTok. OEM notification settings may suppress heads-up
     * banners even after NotificationManager accepted them, so blocked tasks cannot rely on the
     * system notification as their only visible signal.
     */
    private void showBlockedAlert(String reason) {
        hideBlockedAlert();

        alertBanner = new MaterialCardView(context);
        alertBanner.setCardBackgroundColor(Color.rgb(57, 24, 30));
        alertBanner.setRadius(dp(18));
        alertBanner.setStrokeColor(RpaPalette.RED);
        alertBanner.setStrokeWidth(dp(2));
        alertBanner.setCardElevation(dp(12));
        alertBanner.setUseCompatPadding(false);
        alertBanner.setContentPadding(dp(16), dp(13), dp(12), dp(12));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        TextView mark = label("!", 19, Color.WHITE);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(RpaPalette.RED, 15));
        heading.addView(mark, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView title = label("任务需要人工处理", 16, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(32), 1f);
        titleParams.leftMargin = dp(10);
        heading.addView(title, titleParams);

        MaterialButton close = compactAction("×", "关闭人工处理提醒");
        close.setTextSize(20);
        close.setOnClickListener(view -> hideBlockedAlert());
        heading.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        content.addView(heading);

        String detail = reason == null || reason.trim().isEmpty()
                ? "自动化已暂停，请打开助手检查。"
                : reason.trim();
        TextView message = label(detail, 13, Color.rgb(255, 214, 220));
        message.setMaxLines(2);
        message.setGravity(Gravity.START);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(7);
        content.addView(message, messageParams);

        MaterialButton open = compactAction("打开助手处理", "打开 TK养号助手处理阻塞");
        open.setTextSize(13);
        open.setTextColor(Color.WHITE);
        open.setBackgroundTintList(ColorStateList.valueOf(RpaPalette.RED));
        open.setStrokeColor(ColorStateList.valueOf(RpaPalette.RED));
        open.setOnClickListener(view -> {
            hideBlockedAlert();
            actions.onOpenAppRequested();
        });
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        openParams.topMargin = dp(10);
        content.addView(open, openParams);

        alertBanner.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        alertParams = new WindowManager.LayoutParams(
                dp(312),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        alertParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        alertParams.y = dp(56);
        manager.addView(alertBanner, alertParams);
    }

    private void hideBlockedAlert() {
        if (alertBanner == null) return;
        manager.removeView(alertBanner);
        alertBanner = null;
        alertParams = null;
    }

    private LinearLayout.LayoutParams square(int size) {
        return new LinearLayout.LayoutParams(dp(size), dp(size));
    }

    private FrameLayout.LayoutParams matchFrame() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private final class DragListener implements View.OnTouchListener {
        private int initialX;
        private int initialY;
        private float touchX;
        private float touchY;
        private boolean moved;
        private long downTime;

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                initialX = params.x;
                initialY = params.y;
                touchX = event.getRawX();
                touchY = event.getRawY();
                moved = false;
                downTime = event.getEventTime();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                int dx = (int) (event.getRawX() - touchX);
                int dy = (int) (event.getRawY() - touchY);
                moved = moved || Math.abs(dx) + Math.abs(dy) > dp(6);
                params.x = initialX + dx;
                params.y = initialY + dy;
                manager.updateViewLayout(root, params);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (!moved && event.getEventTime() - downTime >= 600) view.performLongClick();
                else if (!moved) view.performClick();
                return true;
            }
            return false;
        }
    }

    private final class PageSwipeListener implements View.OnTouchListener {
        private float downX;
        private float downY;

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float dy = event.getY() - downY;
                float dx = event.getX() - downX;
                if (Math.abs(dy) > dp(24) && Math.abs(dy) > Math.abs(dx)) {
                    setPage(pageIndex + (dy < 0 ? 1 : -1));
                } else {
                    view.performClick();
                }
                return true;
            }
            return event.getAction() == MotionEvent.ACTION_MOVE;
        }
    }

    private static final class DragBubble extends MaterialCardView {
        DragBubble(Context context) { super(context); }
        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private static final class SwipeSurface extends LinearLayout {
        SwipeSurface(Context context) { super(context); }
        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private interface SwipeListener { void onSwipe(int direction); }

    /** Intercepts only deliberate vertical gestures so page buttons keep normal taps. */
    private final class SwipePageHost extends FrameLayout {
        private final SwipeListener listener;
        private float downX;
        private float downY;
        private boolean swiping;

        SwipePageHost(Context context, SwipeListener listener) {
            super(context);
            this.listener = listener;
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                swiping = false;
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float dy = event.getY() - downY;
                float dx = event.getX() - downX;
                swiping = Math.abs(dy) > dp(12) && Math.abs(dy) > Math.abs(dx);
                return swiping;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_MOVE) return true;
            if (event.getAction() == MotionEvent.ACTION_UP && swiping) {
                float dy = event.getY() - downY;
                if (Math.abs(dy) > dp(24)) listener.onSwipe(dy < 0 ? 1 : -1);
                swiping = false;
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) swiping = false;
            if (event.getAction() == MotionEvent.ACTION_UP && !swiping) performClick();
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }

}
