package com.xwell.rpatt.overlay;

/** Commands emitted by the floating UI. Implementations live outside the overlay module. */
public interface OverlayActions {
    void onStartRequested();
    void onPauseResumeRequested();
    void onStopRequested();
    void onChooseProfileRequested();
    void onOpenAppRequested();
    void onCloseRequested();
}
