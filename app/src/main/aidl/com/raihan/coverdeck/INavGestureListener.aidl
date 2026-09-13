package com.raihan.coverdeck;

/**
 * Cover navigation-bar gestures, reported from the shell-uid helper to the app.
 * One-way so a slow app process can never stall the log reader.
 */
oneway interface INavGestureListener {
    /** SystemUI recognised a long press on Home while the phone is folded. */
    void onHomeLongPress();

    /** SystemUI injected a Home key (a tap, or the start/end of a long press). */
    void onHomeKey();

    /** The cover's Back button has been held past One UI's back-cancel threshold. */
    void onBackLongPress();
}
