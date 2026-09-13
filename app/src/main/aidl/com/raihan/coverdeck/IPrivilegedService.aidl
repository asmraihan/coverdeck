package com.raihan.coverdeck;

import com.raihan.coverdeck.model.TaskItem;
import android.view.Surface;
import android.graphics.Bitmap;
import android.content.Intent;
import android.view.MotionEvent;
import com.raihan.coverdeck.INavGestureListener;

interface IPrivilegedService {
    /**
     * Shizuku calls this on the fixed transaction id below when it tears the
     * user service down. It must keep exactly this id.
     */
    void destroy() = 16777114;

    String ping() = 1;
    int getRemoteUid() = 2;
    String exec(String cmd) = 3;

    // ---- rotation -------------------------------------------------------
    int getRotation(int displayId) = 10;
    void freezeRotation(int displayId, int rotation) = 11;
    void thawRotation(int displayId) = 12;
    void setIgnoreOrientationRequest(int displayId, boolean ignore) = 13;
    void setFixedToUserRotation(int displayId, int mode) = 14;
    boolean isRotationFrozen(int displayId) = 15;

    // ---- density --------------------------------------------------------
    int getBaseDensity(int displayId) = 20;
    int getEffectiveDensity(int displayId) = 21;
    void setDensity(int displayId, int density) = 22;
    void resetDensity(int displayId) = 23;

    // ---- recents --------------------------------------------------------
    List<TaskItem> getRecentTasks(int max) = 30;
    Bitmap getTaskSnapshot(int taskId, int maxDim) = 31;
    void launchTask(int taskId, int displayId) = 32;
    void removeTask(int taskId) = 33;
    boolean launchPackage(String packageName, int displayId) = 34;
    void moveTaskToDisplay(int taskId, int displayId) = 35;

    // ---- device state + mirror -----------------------------------------
    List<String> getDeviceStates() = 40;
    int getCurrentDeviceState() = 41;
    void requestDeviceState(int state) = 42;
    void resetDeviceState() = 43;
    boolean startMirror(in Surface surface, int sourceDisplayId, int width, int height, int densityDpi) = 44;
    void stopMirror() = 45;
    String getMirrorEngine() = 46;
    void injectTouch(int action, float x, float y, int targetDisplayId, long downTime, int pointerId) = 47;
    void injectKey(int keyCode, int targetDisplayId) = 48;

    // ---- cover navigation gestures ----------------------------------------
    void startNavWatcher(INavGestureListener listener) = 50;
    void stopNavWatcher() = 51;

    // ---- system surfaces --------------------------------------------------
    /** Closes the notification shade and the cover screen quick panel. */
    void collapseStatusBar() = 52;
    /** Starts an activity on a display as shell, which may start activities from the background. */
    boolean startActivityOnDisplay(in Intent intent, int displayId) = 53;

    // ---- display geometry (works while a panel is off or disabled) ----------
    /** [initialWidth, initialHeight, currentWidth, currentHeight], unrotated. */
    int[] getDisplaySizes(int displayId) = 60;
    void setDisplaySize(int displayId, int width, int height) = 61;
    void resetDisplaySize(int displayId) = 62;

    // ---- mirror ---------------------------------------------------------------
    /** A whole (possibly multi-touch) event, already in target-display coordinates. */
    oneway void injectMotionEvent(in MotionEvent event, int targetDisplayId) = 63;

    /**
     * Keeps the inner display powered while folded (device-state override) or lets it
     * sleep. Returns whether the requested state is in effect.
     */
    boolean setInnerDisplayAwake(boolean awake) = 64;

    /**
     * Switches a display's panel on or off at the SurfaceFlinger level, as scrcpy's
     * "turn screen off" does: the display stays awake and keeps rendering (so it can
     * still be mirrored and controlled), only the physical panel goes dark.
     */
    boolean setDisplayPanelOn(int displayId, boolean on) = 65;

    /**
     * The navigation bar's frame on a display as {left, top, right, bottom}, from the
     * window manager's insets state; empty when the display has none.
     */
    int[] getNavigationBarFrame(int displayId) = 66;
}
