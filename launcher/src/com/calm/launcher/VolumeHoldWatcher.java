package com.calm.launcher;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewConfiguration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restores press-and-hold on the volume buttons.
 *
 * While MiKeyService filters key events, Android handles volume keys inside the accessibility layer and
 * drops them, so neither the service nor the input dispatcher's auto-repeat ever sees them: a held button
 * steps once. The remote itself sends one press and one release per hold. The window manager still logs
 * both ("interceptKeyTi keyCode=24 down=true" ... "down=false"), so we follow that log and add the repeat
 * steps ourselves with Android's own timing. Needs READ_LOGS, granted over ADB. Without it, logcat
 * returns nothing and this does nothing. See follow() for why the log buffer is enlarged.
 */
final class VolumeHoldWatcher {
    private static final Pattern KEY = Pattern.compile("interceptKeyTi keyCode=(24|25) down=(true|false)");
    private static final long SAFETY_MS = 6_000; // stop if the release line never shows up (0 -> 100 takes 5 s)
    private static final long WARMUP_MS = 500;    // skip the one backlog line logcat prints on start
    private static final long STEP_MS = 100;      // stays under what the audio HAL can apply in real time
    private static final int LEVELS_PER_STEP = 2; // 20 levels/s: 0 -> 100 in about 5 s

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile Process process;
    private int direction; // +1 up, -1 down
    private long heldSince;

    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            if (SystemClock.uptimeMillis() - heldSince > SAFETY_MS) return;
            // The TV's audio HAL applies only ~12 changes a second; faster requests queue up and keep
            // changing the volume after release. So send one change per STEP_MS, LEVELS_PER_STEP at a time.
            AudioManager am = context.getSystemService(AudioManager.class);
            int now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int target = Math.max(0, Math.min(max, now + direction * LEVELS_PER_STEP));
            if (target == now) return; // at the limit
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
            handler.postDelayed(this, STEP_MS);
        }
    };

    VolumeHoldWatcher(Context context) {
        this.context = context.getApplicationContext();
    }

    void start() {
        if (running) return;
        running = true;
        new Thread(this::follow, "calm-volume-hold").start();
    }

    void stop() {
        running = false;
        Process p = process;
        if (p != null) p.destroy();
        handler.removeCallbacks(step);
    }

    private void follow() {
        // Each volume step makes the audio stack log dozens of lines into the "main" buffer, the same one
        // the key lines go to. At the stock 256 KB a held button wrapped it in under a second, so a release
        // line could be pruned before we read it and the volume kept going. 2 MB gives several seconds of slack.
        // Needs the log group from READ_LOGS and doesn't survive a reboot, so redo it every start.
        try {
            new ProcessBuilder("logcat", "-b", "main", "-G", "2M").start().waitFor();
        } catch (Exception ignored) {
        }
        while (running) {
            try {
                process = new ProcessBuilder("logcat", "-b", "main,system", "-v", "raw", "-T", "1",
                        "-s", "WindowManager:D").redirectErrorStream(true).start();
                long started = SystemClock.uptimeMillis();
                BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while (running && (line = in.readLine()) != null) {
                    if (SystemClock.uptimeMillis() - started < WARMUP_MS) continue;
                    Matcher m = KEY.matcher(line);
                    if (!m.find()) continue;
                    final boolean up = m.group(1).equals("24");
                    final boolean down = m.group(2).equals("true");
                    handler.post(() -> onVolumeKey(up, down));
                }
            } catch (Exception ignored) {
            } finally {
                Process p = process;
                if (p != null) p.destroy();
            }
            if (running) SystemClock.sleep(5000); // logcat exited: restart it
        }
    }

    private void onVolumeKey(boolean up, boolean down) {
        handler.removeCallbacks(step);
        if (!down) return;
        // The press itself already stepped once; start repeating after the normal key-repeat timeout.
        direction = up ? 1 : -1;
        heldSince = SystemClock.uptimeMillis();
        handler.postDelayed(step, ViewConfiguration.getKeyRepeatTimeout());
    }
}
