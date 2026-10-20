package com.calm.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remaps the remote's hotkeys to apps of our choosing.
 *
 * Xiaomi's GlobalKeyReceiver normally handles these keys (on Indian firmware the Mi button is hard-wired
 * to PatchWall, falling back to the stock Live TV app on HDMI 1). Accessibility key filtering runs before
 * the system's global-key routing, so consuming a key here overrides it. Keys not in REMAP pass through.
 */
public class MiKeyService extends AccessibilityService {

    /** Remote key -> package to open. Edit this table to remap buttons. */
    private static final SparseArray<String> REMAP = new SparseArray<>();
    static {
        REMAP.put(KeyEvent.KEYCODE_BUTTON_5, "com.mitv.tvhome.mitvplus");      // Mi button -> Mi TV+
        REMAP.put(KeyEvent.KEYCODE_BUTTON_4, "com.google.android.youtube.tv"); // Prime Video button -> YouTube
    }

    // Key filtering costs held volume buttons their auto-repeat; this puts it back (see the class).
    private VolumeHoldWatcher volumeHold;

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = 0; // keys only; no UI events, no window content
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        volumeHold = new VolumeHoldWatcher(this);
        volumeHold.start();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String pkg = REMAP.get(event.getKeyCode());
        if (pkg == null) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            Intent target = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (target != null) {
                target.putExtra("activity_from", "miKey"); // what Xiaomi's own handler sends
                android.util.Log.i("CalmMiKey", KeyEvent.keyCodeToString(event.getKeyCode()) + " -> " + pkg);
            } else { // app missing or disabled: go Home instead
                target = new Intent(this, MainActivity.class)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME);
                android.util.Log.i("CalmMiKey", KeyEvent.keyCodeToString(event.getKeyCode()) + " -> Home (" + pkg + " unavailable)");
            }
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            } catch (Exception e) {
                android.util.Log.w("CalmMiKey", "launch failed", e);
            }
        }
        return true; // consume both down and up
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        if (volumeHold != null) volumeHold.stop();
        return super.onUnbind(intent);
    }
}
