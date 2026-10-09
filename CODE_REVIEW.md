# CoverDeck code review

Reviewed 2026-09-21 at commit `cf47cc9` (clean tracked worktree at review start).

The core feature implementation is substantial and tailored to the Galaxy Z Flip 5. The principal weaknesses are cancellation, restoration, and error reporting around privileged operations. These need attention before treating the app as a dependable replacement for a broken inner screen.

Scope: all 30 Kotlin files, AIDL contracts, manifest, resources, Gradle configuration, ProGuard rules, and release workflow/documentation. No production code or phone settings were changed. This report and an ignored build-directory scheduling probe were added. Findings below are source-level defects unless explicitly identified as observed build results; failure scenarios were not induced on the phone.

## Prioritized findings

### 1. P1 — A suspended shape update can change the inner display after Stop restores it

Locations: [MirrorSession.kt:74](app/src/main/java/com/raihan/coverdeck/mirror/MirrorSession.kt#L74), [shape correction:327](app/src/main/java/com/raihan/coverdeck/mirror/MirrorSession.kt#L327), [navigation measurement:369](app/src/main/java/com/raihan/coverdeck/mirror/MirrorSession.kt#L369).

`limitedParallelism(1)` limits simultaneous thread execution; it does not serialize complete suspending operations. Each measurement, shape toggle, and stop launches a separate coroutine. With Hide navigation bar enabled, `applyShape()` suspends inside `measureNavBar()`. `end()` can run during that suspension, restore the original display, and clear the recovery records. The old shape coroutine can then resume and call `setDisplaySize()` again, leaving the inner display reshaped after mirroring has stopped. Multiple rapid shape changes can also apply stale calculations out of order.

Use explicit session serialization, such as a command consumer or mutex, together with a session generation and cancellation/join of obsolete shape work. Avoid holding a mutex across a callback that needs the same mutex. Stop must prevent further writes before restoring the display. An isolated Kotlin probe reproduced the scheduling invariant. [Kotlin documents this exact dispatcher limitation.](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/limited-parallelism.html)

### 2. P1 — Notification Stop can cancel its own mirror restoration

Locations: [CoverDeckService.kt:117](app/src/main/java/com/raihan/coverdeck/overlay/CoverDeckService.kt#L117), [MirrorSession.kt:163](app/src/main/java/com/raihan/coverdeck/mirror/MirrorSession.kt#L163).

`stopEverything()` launches `MirrorSession.end()` in the service's `lifecycleScope` and then calls `stopSelf()` without awaiting cleanup. Service destruction cancels that scope. `end()` switches to Main to remove the window before executing privileged restoration, so cancellation can prevent cleanup from starting or interrupt it at that handoff. The notification can disappear while the mirror or altered display settings remain. Startup recovery is not an immediate remedy and currently ignores a session owned by the same still-running app PID.

Finish cleanup before stopping the service, or give the session its own cleanup job independent of the service lifecycle. Preserve recovery records if cleanup cannot finish. The scheduling probe confirmed that cancellation at the UI handoff skips restoration. [Android documents lifecycle-scope cancellation on destruction.](https://developer.android.com/topic/libraries/architecture/views/coroutines-views)

### 3. P1 — Failed restoration discards the information needed to retry

Locations: [MirrorSession.kt:170](app/src/main/java/com/raihan/coverdeck/mirror/MirrorSession.kt#L170), [recovery:276](app/src/main/java/com/raihan/coverdeck/mirror/MirrorSession.kt#L276), [RotationController.kt:198](app/src/main/java/com/raihan/coverdeck/feature/RotationController.kt#L198).

If Shizuku disconnects before Stop, `Privileged.with` returns null and the geometry cannot be restored. Nevertheless, `end()` removes `KEY_OWNER_PID`. `KEY_HELD` may remain true, but `recoverStranded()` only enters when an owner PID exists, so subsequent startup recovery skips those originals. `recoverStranded()` itself also removes the owner even if restoration fails. Rotation reset separately deletes its saved original values unconditionally after a failed privileged call, permanently losing that record.

Represent pending restoration independently of a process PID. Return restoration results, retain each saved original until its corresponding write succeeds, and retry when the privileged connection returns. Recovery should run from an application-level connection observer, not exclusively from the foreground main activity. A successful Binder transaction alone is also insufficient while shell failures are swallowed; see finding 6.

### 4. P2 — An active mirror never handles loss or replacement of its Shizuku helper

Locations: [Privileged.kt:92](app/src/main/java/com/raihan/coverdeck/privileged/Privileged.kt#L92), [MirrorWindow.kt:433](app/src/main/java/com/raihan/coverdeck/mirror/MirrorWindow.kt#L433), [CoverDeckService.kt:53](app/src/main/java/com/raihan/coverdeck/overlay/CoverDeckService.kt#L53).

Binder death updates `Privileged.status`, but neither MirrorSession nor MirrorWindow observes it. The mirror retains its successful `streaming` flag and can continue reporting Live over a frozen or blank picture. Reconnection restarts the rotator and navigation watcher only; the new helper has no virtual display, and `startStreamIfReady()` refuses to start while its old `streaming` flag remains true.

On helper loss, invalidate the stream generation and publish a disconnected state. On reconnection, either rebuild the active session and surface binding or complete pending restoration and clearly stop the mirror. Do not rely on a later power or layout transition to repair the connection.

### 5. P2 — Blocking privileged work still runs directly on the main thread

Locations: [DeckViewModel.kt:83](app/src/main/java/com/raihan/coverdeck/ui/DeckViewModel.kt#L83), [ScreenTimeout.kt:109](app/src/main/java/com/raihan/coverdeck/feature/ScreenTimeout.kt#L109), [RecentsPanel.kt:47](app/src/main/java/com/raihan/coverdeck/recents/RecentsPanel.kt#L47), [Hidden.kt:204](app/src/main/java/com/raihan/coverdeck/privileged/Hidden.kt#L204).

UI callbacks synchronously execute timeout writes, rotation changes, task launch/removal, accessibility enablement, and some connection initialization. Moving the implementation into another process does not make a synchronous AIDL call asynchronous. Several paths spawn `settings`, `wm`, or `am`; `Hidden.sh()` reads to EOF and waits without a deadline. Slow system services or fallback commands therefore block drawing and input and can cause an ANR. The rotator's Handler also freezes rotation on Main.

Move blocking operations onto owned workers, serialize conflicting changes, return results to Main, and give shell processes bounded lifetimes with concurrent output drainage. The oneway MotionEvent path is a different case and need not be treated as a synchronous shell call. No ANR was reproduced during this read-only review.

### 6. P2 — Shell failures are reported as successful setting changes

Locations: [Hidden.kt:204](app/src/main/java/com/raihan/coverdeck/privileged/Hidden.kt#L204), [ScreenTimeout.kt:109](app/src/main/java/com/raihan/coverdeck/feature/ScreenTimeout.kt#L109), [MirrorSession.kt:145](app/src/main/java/com/raihan/coverdeck/mirror/MirrorSession.kt#L145).

`Hidden.sh()` discards exit status and converts exceptions into ordinary strings. Most setting callers ignore even that output and return success if Binder did not throw. For example, either timeout write can fail while `ScreenTimeout.write()` returns true; restoration then clears its saved originals. Mirroring similarly ignores the Boolean from `setInnerDisplayAwake(true)` and continues opening the window and modifying geometry even when the source display could not be activated.

Use a structured command result and verify critical settings after writes. Stop startup and undo completed steps when acquiring the source display fails. Make AIDL mutation methods communicate failure instead of silently swallowing it.

### 7. P2 — Returning to the current orientation does not cancel a pending rotation

Location: [CoverAutoRotator.kt:339](app/src/main/java/com/raihan/coverdeck/feature/CoverAutoRotator.kt#L339).

When 0 degrees is already applied, a 90-degree reading schedules a rotation after 250 ms. If the sensor returns to 0 before that delay expires, `target == lastApplied` returns before removing the pending callback. The earlier 90-degree target still executes even though the latest reading says 0. Entering a disallowed upside-down quadrant has the same stale-callback problem. With an on-change sensor, the incorrect rotation can persist until the next reading.

Cancel or replace pending work on every new reading, including rejected/current targets, and validate the target against the latest reading when the callback fires.

### 8. P2 — Gesture toggling leaks worker threads and allows work after Stop

Locations: [NavGestureWatcher.kt:40](app/src/main/java/com/raihan/coverdeck/nav/NavGestureWatcher.kt#L40), [stop:78](app/src/main/java/com/raihan/coverdeck/nav/NavGestureWatcher.kt#L78).

Each watcher creates a single-thread executor. Once a Back hold uses it, that executor owns a live thread. `stop()` neither shuts it down nor cancels queued rotation actions. Turning both gestures off discards the watcher, and turning one back on creates another executor; repeated use accumulates idle threads. A queued old action can also apply rotation after the watcher was stopped or settings were reset.

Shut down the executor when discarding its watcher and reject stale work with an ownership/generation check. Remove queued Handler callbacks or have handlers validate that the watcher is still active. Daemon threads still consume resources while the process lives.

### 9. P2 — Reset does not turn off saved gestures when the service is absent

Locations: [DeckViewModel.kt:143](app/src/main/java/com/raihan/coverdeck/ui/DeckViewModel.kt#L143), [CoverDeckService.kt:194](app/src/main/java/com/raihan/coverdeck/overlay/CoverDeckService.kt#L194).

Reset relies on `CoverDeckService.stop()` to clear Home/Back preferences inside `stopEverything()`. That call does nothing when `isRunning` is false. After a process restart, before Shizuku becomes ready, the stored gestures can be enabled with no service running. Reset then leaves them enabled, and `resumePersistentFeatures()` starts them again when Shizuku connects. This contradicts the reset dialog.

Clear persistent feature switches directly in the reset coordinator regardless of service state. Await mirror restoration before publishing a successful reset notice, and report pending restoration separately if privileges are unavailable.

### 10. P2 — Fresh installations have no notification permission request

Locations: [AndroidManifest.xml:8](app/src/main/AndroidManifest.xml#L8), [notification actions:170](app/src/main/java/com/raihan/coverdeck/overlay/CoverDeckService.kt#L170).

`POST_NOTIFICATIONS` is declared, but the source has no runtime request or notification-settings setup flow. On a fresh Android 13+ installation without a grant, the foreground service may run while its notification-drawer controls are absent. Users therefore cannot access the advertised Recents/Mirror/Stop actions through the notification.

Request the permission from a foreground, user-initiated setup flow and explain unavailable controls if it is denied. This does not mean foreground-service startup itself requires the permission. [Android notification permission behavior.](https://developer.android.google.cn/develop/ui/compose/notifications/notification-permission?hl=en)

### 11. P2 — The fallback decoder reports Live before producing a frame

Locations: [MirrorEngine.kt:232](app/src/main/java/com/raihan/coverdeck/privileged/MirrorEngine.kt#L232), [queue:259](app/src/main/java/com/raihan/coverdeck/privileged/MirrorEngine.kt#L259).

The first SPS/PPS configuration NAL triggers `onFirstData()` and releases the supposed first-frame latch. Moreover, `queue()` can return without queuing anything when no input buffer is available, and its caller still marks success. A stream containing headers but no decoded image can therefore make `start()` return true and the UI show Live. Subsequent terminal decoder failure is also not propagated to the session.

Declare startup success only after a decoded output frame is submitted for rendering, propagate later failure, and distinguish input backpressure from successfully queued data. Header parsing alone is not frame validation. [MediaCodec describes codec-specific configuration separately from decoded output.](https://developer.android.com/reference/android/media/MediaCodec?authuser=2)

### 12. P2 — Retry cannot recover a connection whose initial handshake failed

Locations: [Privileged.kt:70](app/src/main/java/com/raihan/coverdeck/privileged/Privileged.kt#L70), [refresh:125](app/src/main/java/com/raihan/coverdeck/privileged/Privileged.kt#L125).

`onServiceConnected()` stores `service = remote` before querying UID and ping. If either call fails, status becomes Failed but service remains non-null. Setup's Retry invokes `refresh()`, which only binds when service is null and does not retry the handshake. While that reference remains, Retry cannot leave the failed state.

Publish the remote only after a successful handshake, clear failed references, and give refresh an explicit reconnect path. Observe death of the user-service Binder independently from death of the Shizuku server Binder.

## What has already been implemented, and how

| Area | Current implementation |
| --- | --- |
| Application/UI | One Android application module, Kotlin and Compose, dark Material 3 theme, adaptive home grid, Rotation/Recents/Mirror/Timeout/Setup routes. State is mainly process-wide StateFlows and SharedPreferences, coordinated by DeckViewModel. |
| Privileged execution | `Privileged` connects through Shizuku to `PrivilegedService`, an AIDL Stub loaded into a separate process. The intended execution identity is shell UID 2000. This is an installed app plus privileged helper, not a modified ROM or framework service. |
| Framework bridge | `Hidden` obtains WindowManager, ActivityTaskManager and other services, probes reflection signatures, and falls back to shell commands. `ShellContext` supplies shell package/attribution identity for display creation. |
| Display discovery | Inner display is fixed to logical ID 0. Cover is selected from other internal displays, with Flip 5-specific fallback ID and dimensions. Hardware mode dimensions avoid misleading cross-display app metrics. WindowManager provides disabled-display geometry. |
| Cover rotation | Manual 0/90/180/270-degree locks; Samsung System mode; custom Auto mode. Locks combine freeze rotation, ignore orientation requests, and fixed-to-user rotation. Auto uses Samsung orientation sensor type 27 or an accelerometer fallback, 250 ms settling, optional upside-down rotation, and two-position calibration. Latest changes remap the sensor frame while the inner display is on. |
| Persistent cover features | A `specialUse` foreground LifecycleService owns the sensor loop and navigation watcher. Feature choices survive process restarts; the main activity resumes them after privileges connect. Cover-off transitions pause sensors/log watching. |
| Navigation gestures | Shell tails two SystemUI/window-manager log tags. Home long press opens recents; Home tap dismisses it. Back uses a 700 ms hold timer and toggles Auto/0 degrees. Generation tracking and Binder listener death handling exist on the log-reader side. HUD confirmations use accessibility overlays, with a toast fallback. |
| Recents | Real recent-task data and snapshots come from ActivityTaskManager. Labels/icons load before thumbnails. A separate translucent exported activity is launched onto the cover as shell after collapsing the shade. Cards support launch, swipe removal, close displayed unkept tasks, app info, and opening on the inner display. Keep open is a local package exclusion from CoverDeck's Close all; it does not pin processes against Android memory reclamation. |
| Mirror host/layout | A minimal accessibility service hosts a full-screen accessibility overlay. A SurfaceView carries the mirror; a custom Canvas view draws camera-cutout-aware navigation and menu controls. Whole MotionEvents are transformed into source coordinates and sent over oneway AIDL, preserving multiple pointers. |
| Mirror capture | VirtualDisplay mirroring is attempted first; shell-context auto-mirroring is the next virtual-display path. If these fail, screenrecord emits H.264 for MediaCodec decoding. Screenrecord uses the physical display ID and restarts its time-limited recording sessions. |
| Mirror geometry | Device-state override requests a concurrent/opened state to keep the inner display rendering while folded. Fit mode reshapes it to the measured cover area at 450 dpi; Original restores saved size/density while retaining session orientation lock. Hide nav bar grows the source and crops the measured bottom bar. |
| Panel power | SurfaceFlinger display power control can darken only the inner physical panel while rendering and injected input continue. On Android 14+, the helper loads DisplayControl from services.jar and its native library. A session job reapplies panel-off every three seconds. |
| Restoration | Mirror originals include size override, density, and rotation settings; an owner PID marks potentially stranded sessions. Rotation and screen timeout separately remember originals before first mutation. The intended recovery behavior is clear, but findings 1–3 show why it is not yet reliable. |
| Timeout | Writes Samsung `cover_screen_timeout` in seconds and Android `screen_off_timeout` in milliseconds. The second setting affects the phone generally, including the inner screen. Content observers refresh the UI when these settings change elsewhere. |
| Packaging/release | SDK 33 minimum, compile/target 36, Java 17, AGP 8.12, Kotlin 2.2.10. Git tags determine release version/code; the workflow reconstructs a signing key from secrets and publishes an APK. Local release builds may use the debug key; CI assemble/bundle release checks reject missing release signing configuration. Minification is disabled. |

## Design choices worth preserving

- The separation between ordinary UI/window hosting and shell-only framework operations gives the app a clear privilege boundary.
- Activity-based recents and accessibility-overlay mirroring address distinct Samsung window restrictions; replacing both with ordinary app overlays would undo those accommodations.
- `callVoid()` correctly distinguishes successful void reflection calls from missing methods, avoiding unnecessary fallback processes.
- Snapshot downscaling, progressive recents loading, and whole-event touch forwarding are already implemented.
- The shell navigation reader has generation tracking, process cleanup, retry backoff, and listener death handling; similar ownership discipline is needed throughout mirroring.
- Saving originals synchronously before persistent mutations is appropriate. Do not blindly replace every `commit()` with `apply()` just to silence lint; instead perform the write off Main and check its result.

## Security and compatibility assessment

The accessibility service requires `BIND_ACCESSIBILITY_SERVICE`, does not request window-content retrieval, and ignores its declared accessibility events. The foreground controller is not exported, the Shizuku provider is protected by `INTERACT_ACROSS_USERS_FULL`, and notification PendingIntents are immutable. The recents activity is exported for shell launch, but its code does not accept arbitrary privileged commands from Intent extras. I did not establish an external-app path to the helper's generic `exec()` interface; it is still a powerful capability that must remain confined to the authorized Binder connection.

The implementation is device-specific: display fallbacks, device-state names, log markers/timing, navigation-bar dumpsys parsing, and panel-power reflection all depend on firmware behavior. Minimum SDK 33 is a build/install declaration, not evidence of successful operation on every Android 13–16 phone. The connected SM-F731B reports Android 16, SDK 36, and One UI property `80500`, matching the comments. The installed app reports version 1.0.0/code 10000 and is debuggable; its APK was not compared byte-for-byte with this checkout.

Additional hardening/coverage observations, distinct from the prioritized failures:

- Backup currently includes preferences by default. Session ownership and saved physical-device originals should be excluded from cloud/device-transfer backup; restored records should not be applied to another device.
- User ID 0 is hard-coded for recent-task enumeration and density operations. TaskItem carries a user ID, but profile-aware labels/actions and Keep open keys are incomplete. Test work profiles before promising support.
- Custom Canvas mirror controls lack accessibility node semantics and actions. Window hosting via AccessibilityService does not make those drawn controls accessible to TalkBack.
- The ten-argument KeyEvent constructor in `PrivilegedService.injectKey()` places `FLAG_FROM_SYSTEM` in the deviceId argument and passes zero for flags. Correct the argument order; the practical effect on this firmware was not tested.
- Screenrecord worker shutdown, decoder backpressure, output draining, and Binder-side mirror start/stop synchronization merit targeted stress tests. The current single shared running flag and bounded join do not establish that an old decoder worker has terminated before a new one starts.
- Several comments describe previous UI versions: the inner Rotation tab, an in-app recents renderer, and density controls are not current exposed pages. Legacy AIDL helpers remain, including single-point touch injection and explicit task moving.

## Validation performed

Command: `gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest --console=plain`.

| Check | Result |
| --- | --- |
| Debug APK | `:app:assembleDebug` succeeded. Some compilation tasks were satisfied from Gradle's cache. |
| Android lint | Failed: 2 errors, 56 warnings. |
| Unit tests | `:app:testDebugUnitTest NO-SOURCE`; no tests ran. No checked-in unit or instrumentation test source sets were found. |
| Coroutine probes | Two isolated JVM probes passed with Kotlin 2.2.10 and coroutines 1.9.0, demonstrating interleaving after suspension and cleanup cancellation. These model scheduling, not Android framework calls. |
| Device inspection | Read-only model/firmware/package metadata and a bounded, tag-filtered logcat read. The selected log window returned no matching warnings/crashes; this does not establish a clean historical crash record. |

Lint errors:

1. Machine-local, ignored `local.properties:3`: `PropertyEscape` for the unescaped drive-letter colon. This is a local lint blocker, not a tracked source defect.
2. Tracked [MirrorEngine.kt:69](app/src/main/java/com/raihan/coverdeck/privileged/MirrorEngine.kt#L69): `WrongConstant` for the custom AUTO_MIRROR constant passed to the framework flag argument. The numeric bit is the intended auto-mirror bit; this is a lint/static-annotation issue, not evidence of a wrong runtime flag. Use the named public constant where available.

Many warnings are dependency-update suggestions or style suggestions. The reported static Context leak in RecentsPanel refers to a controller constructed with applicationContext, so it is not evidence of an Activity leak. Review intentional private API warnings individually instead of removing the privileged behavior or broadly suppressing all lint. The release workflow currently only assembles; it has no lint/test gate.

Local-only artifacts (excluded from Git): `app/build/reports/lint-results-debug.html` and `app/build/review/ConcurrencyProbe.kt`.

No APK was installed, no UI interaction test was performed, and no process-death, fold/unfold, power, or Shizuku failure was deliberately triggered on the connected phone. Release signing/publication was not exercised.

## Recommended verification after fixes

First test session ownership and restoration using a fake privileged backend: Stop during nav-bar measurement; rapid shape changes; duplicate starts; helper loss during each mutation; service destruction during cleanup; retry with pending originals; and failed preference commits. Assert both the final state and the absence of privileged writes after Stop completes.

Then run device tests on this firmware: active mirroring with Settings, all cover rotations and cutout positions, multitouch while geometry changes, sleep/wake, physical unfolding, host disablement, Shizuku restart, and stop/reset with the main activity absent. Test both capture engines, a configuration-only H.264 stream, and recording rollover. Finally verify fresh-install notification setup and repeated gesture enable/use/disable cycles. These are the main gaps between a successful build and confidence that the cover remains usable under failure.
