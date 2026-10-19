package com.calm.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The home screen: rotating nature wallpaper, a clock, and a dock of favourite apps. */
public class MainActivity extends Activity {
    private static final long ROTATE_MS = 5 * 60 * 1000L;
    private static final long FADE_MS = 1500L;
    private static final String KEY_WALL = "wall_current";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private ImageView wallA, wallB;
    private boolean showingB;
    private TextView credit;
    private LinearLayout dock;
    private int focusIndex;
    private String builtDock = "";
    private int screenW, screenH;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            nextWallpaper(false);
            ui.postDelayed(this, ROTATE_MS);
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            buildDock();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);

        wallA = wallView();
        wallB = wallView();
        wallB.setAlpha(0f);
        root.addView(wallA, match());
        root.addView(wallB, match());

        // Soft scrims so white text and the dock read on any photo.
        root.addView(scrim(GradientDrawable.Orientation.BOTTOM_TOP, 0xB3000000), new FrameLayout.LayoutParams(-1, screenH * 45 / 100, Gravity.BOTTOM));
        root.addView(scrim(GradientDrawable.Orientation.TOP_BOTTOM, 0x73000000), new FrameLayout.LayoutParams(-1, screenH * 38 / 100, Gravity.TOP));

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.END);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        styleText(time, 64, "sans-serif-thin", 0xFFFFFFFF);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        styleText(date, 16, "sans-serif-light", 0xDDFFFFFF);
        clockCol.addView(time);
        clockCol.addView(date);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(28), dp(48), 0);
        root.addView(clockCol, clp);

        credit = new TextView(this);
        styleText(credit, 9, "sans-serif", 0x99FFFFFF);
        credit.setSingleLine(true);
        credit.setMaxWidth(screenW * 6 / 10);
        credit.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams crp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        crp.setMargins(dp(24), 0, 0, dp(10));
        root.addView(credit, crp);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setPadding(dp(32), dp(28), dp(32), dp(10));
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.BOTTOM);
        dock.setClipChildren(false);
        scroller.addView(dock, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = dp(30);
        root.addView(scroller, dlp);

        setContentView(root);
        buildDock();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);

        WallpaperJob.schedule(this);
        nextWallpaper(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ui.removeCallbacks(rotate);
        ui.postDelayed(rotate, ROTATE_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(rotate); // nothing animates while another app is in front
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!builtDock.equals(Apps.dock(this).toString())) buildDock(); // edited from All Apps
        focusDock();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        focusIndex = 0; // HOME pressed while already home: back to the first app
        focusDock();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // This is home; there is nothing behind it.
    }

    // ---- dock ----

    private void buildDock() {
        dock.removeAllViews();
        int cardW = dp(140), cardH = dp(79), tileW = dp(72), gap = dp(20);
        List<String> pkgs = Apps.dock(this);
        builtDock = pkgs.toString();
        int slot = 0;
        for (final String pkg : pkgs) {
            final Apps.App a = Apps.load(this, pkg);
            if (a == null) continue;
            View card = Apps.card(this, a, cardW, cardH);
            final int index = slot;
            card.setOnClickListener(v -> startSafely(a.launch));
            card.setOnLongClickListener(v -> {
                editDock(pkg, a.label);
                return true;
            });
            card.setOnKeyListener((v, code, ev) -> {
                if (code == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) {
                    editDock(pkg, a.label);
                    return true;
                }
                return false;
            });
            addSlot(card, a.label, gap, index);
            slot++;
        }
        View apps = Apps.textCard(this, "▦", "All Apps", tileW, cardH);
        apps.setOnClickListener(v -> startSafely(new Intent(this, AllAppsActivity.class)));
        addSlot(apps, "All Apps", gap, slot++);
        View inputs = Apps.textCard(this, "⇄", "Inputs", tileW, cardH);
        inputs.setOnClickListener(v -> pickInput());
        addSlot(inputs, "Inputs", gap, slot++);
        View settings = Apps.textCard(this, "⚙", "Settings", tileW, cardH);
        settings.setOnClickListener(v -> startSafely(new Intent(Settings.ACTION_SETTINGS)));
        addSlot(settings, "Settings", 0, slot);
        focusDock();
    }

    private void addSlot(View card, String label, int gapAfter, final int index) {
        LinearLayout slot = new LinearLayout(this);
        slot.setOrientation(LinearLayout.VERTICAL);
        slot.setGravity(Gravity.CENTER_HORIZONTAL);
        slot.setClipChildren(false);
        slot.addView(card);
        final TextView name = new TextView(this);
        styleText(name, 12, "sans-serif-medium", 0xFFFFFFFF);
        name.setText(label);
        name.setAlpha(0f);
        name.setSingleLine(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = dp(14);
        slot.addView(name, nlp);
        card.setOnFocusChangeListener((v, has) -> {
            Apps.setLifted(this, v, has);
            name.animate().alpha(has ? 1f : 0f).setDuration(150).start();
            if (has) focusIndex = index;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = gapAfter;
        dock.addView(slot, lp);
    }

    private void focusDock() {
        if (dock == null || dock.getChildCount() == 0) return;
        int i = Math.min(focusIndex, dock.getChildCount() - 1);
        View card = ((ViewGroup) dock.getChildAt(i)).getChildAt(0);
        card.post(card::requestFocus);
    }

    private void editDock(final String pkg, String label) {
        final List<String> pkgs = Apps.dock(this);
        final int i = pkgs.indexOf(pkg);
        if (i < 0) return;
        String[] options = {"Move left", "Move right", "Remove from dock"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label)
                .setItems(options, (d, which) -> {
                    if (which == 0 && i > 0) {
                        pkgs.add(i - 1, pkgs.remove(i));
                        focusIndex = i - 1;
                    } else if (which == 1 && i < pkgs.size() - 1) {
                        pkgs.add(i + 1, pkgs.remove(i));
                        focusIndex = i + 1;
                    } else if (which == 2) {
                        pkgs.remove(i);
                    }
                    Apps.saveDock(this, pkgs);
                    buildDock();
                })
                .show();
    }

    /** HDMI/AV switcher (it used to live in the stock launcher's top bar). */
    private void pickInput() {
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return;
        final List<TvInputInfo> inputs = new java.util.ArrayList<>();
        List<String> labels = new java.util.ArrayList<>();
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!info.isPassthroughInput() || info.isHidden(this)) continue;
            String label = String.valueOf(info.loadLabel(this));
            if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_DISCONNECTED) label += "   (nothing connected)";
            inputs.add(info);
            labels.add(label);
        }
        if (inputs.isEmpty()) {
            startSafely(new Intent(Settings.ACTION_SETTINGS));
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Inputs")
                .setItems(labels.toArray(new String[0]), (d, which) -> startSafely(new Intent(Intent.ACTION_VIEW,
                        TvContract.buildChannelUriForPassthroughInput(inputs.get(which).getId()))))
                .show();
    }

    private void startSafely(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Can't open that app", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ---- wallpaper ----

    private void nextWallpaper(final boolean first) {
        final Context c = getApplicationContext();
        bg.execute(() -> {
            List<String> walls = Wallpapers.list(c);
            if (walls.isEmpty()) return;
            // Track the current file, not a position: daily downloads are prepended and would shift indices.
            String current = Apps.prefs(c).getString(KEY_WALL, "");
            int idx = Math.max(walls.indexOf(current), 0);
            if (!first && walls.contains(current)) idx++;
            for (int tries = 0; tries < walls.size(); tries++, idx++) {
                final String src = walls.get(Math.floorMod(idx, walls.size()));
                final Bitmap bmp = Wallpapers.decode(c, src, screenW, screenH);
                if (bmp == null) continue; // unreadable file: skip to the next one
                Apps.prefs(c).edit().putString(KEY_WALL, src).apply();
                android.util.Log.i("CalmWallpapers", (first ? "show " : "rotate -> ") + src + " (" + walls.size() + " available)");
                final String who = Wallpapers.credit(c, src);
                ui.post(() -> show(bmp, who, first));
                return;
            }
        });
    }

    private void show(Bitmap bmp, String who, boolean first) {
        ImageView incoming = showingB ? wallA : wallB;
        incoming.setImageBitmap(bmp);
        // wallB sits on top: fade it in to show B, fade it out to reveal A.
        float target = showingB ? 0f : 1f;
        wallB.animate().alpha(target).setDuration(first ? 400 : FADE_MS).withLayer().start();
        showingB = !showingB;
        credit.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            credit.setText(who);
            credit.animate().alpha(1f).setDuration(600).start();
        }).start();
    }

    // ---- small helpers ----

    private ImageView wallView() {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return iv;
    }

    private View scrim(GradientDrawable.Orientation o, int color) {
        View v = new View(this);
        v.setBackground(new GradientDrawable(o, new int[] {color, 0x00000000}));
        return v;
    }

    private void styleText(TextView t, float sp, String family, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        t.setShadowLayer(dp(6), 0, dp(1), 0x66000000);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private int dp(float v) {
        return Apps.dp(this, v);
    }
}
