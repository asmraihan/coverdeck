package com.raihan.coverdeck;

import com.raihan.coverdeck.model.TaskItem;
import android.view.Surface;
import android.graphics.Bitmap;

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
}
