package com.calm.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Launchable apps, the saved dock, and the rounded "card" views both screens share. */
final class Apps {
    private static final String PREFS = "calm";
    private static final String KEY_DOCK = "dock";
    // Chosen with the user; editable on the TV via long-press.
    private static final String DEFAULT_DOCK =
            "com.netflix.ninja,com.google.android.youtube.tv,com.amazon.amazonvideo.livingroom,in.startv.hotstar";

    static final class App {
        String pkg;
        String label;
        Intent launch;
        Drawable banner;
        Drawable icon;
    }

    private Apps() {}

    static App load(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return null;
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        if (!ai.enabled) return null;
        App a = new App();
        a.pkg = pkg;
        a.label = pm.getApplicationLabel(ai).toString();
        a.launch = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            a.banner = pm.getActivityBanner(launch);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (a.banner == null) a.banner = pm.getApplicationBanner(ai);
        a.icon = pm.getApplicationIcon(ai);
        return a;
    }

    static List<App> all(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Boolean> pkgs = new LinkedHashMap<>();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) pkgs.put(ri.activityInfo.packageName, true);
        }
        pkgs.remove(c.getPackageName());
        List<App> out = new ArrayList<>();
        for (String p : pkgs.keySet()) {
            App a = load(c, p);
            if (a != null) out.add(a);
        }
        final Collator col = Collator.getInstance();
        Collections.sort(out, (x, y) -> col.compare(x.label, y.label));
        return out;
    }

    static List<String> dock(Context c) {
        String s = prefs(c).getString(KEY_DOCK, DEFAULT_DOCK);
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void saveDock(Context c, List<String> pkgs) {
        prefs(c).edit().putString(KEY_DOCK, TextUtils.join(",", pkgs)).apply();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- views ----

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** A rounded, clipped card showing the app's TV banner, or its icon on a frosted tile. */
    static FrameLayout card(Context c, App a, int w, int h) {
        FrameLayout card = baseCard(c, w, h);
        if (a.banner != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(a.banner);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(a.icon);
            int s = Math.min(w, h) * 6 / 10;
            card.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        return card;
    }

    /** A text-only utility tile ("All Apps", "Settings"). */
    static FrameLayout textCard(Context c, String glyph, String text, int w, int h) {
        FrameLayout card = baseCard(c, w, h);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        TextView g = new TextView(c);
        g.setText(glyph);
        g.setTextColor(Color.WHITE);
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        g.setGravity(Gravity.CENTER);
        col.addView(g);
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(0xDDFFFFFF);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        card.addView(col, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        return card;
    }

    private static FrameLayout baseCard(Context c, int w, int h) {
        final int r = dp(c, 12);
        FrameLayout card = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x40FFFFFF);
        bg.setCornerRadius(r);
        card.setBackground(bg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        card.setClipToOutline(true);
        card.setElevation(dp(c, 4));
        card.setFocusable(true);
        card.setFocusableInTouchMode(false);
        card.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return card;
    }

    /** Apple TV-style lift: scale up, deeper shadow, thin bright rim. */
    static void setLifted(Context c, View card, boolean lifted) {
        card.animate()
                .scaleX(lifted ? 1.12f : 1f)
                .scaleY(lifted ? 1.12f : 1f)
                .translationZ(lifted ? dp(c, 20) : 0)
                .setDuration(150)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        if (lifted) {
            GradientDrawable rim = new GradientDrawable();
            rim.setCornerRadius(dp(c, 12));
            rim.setStroke(dp(c, 2), 0xCCFFFFFF);
            card.setForeground(rim);
        } else {
            card.setForeground(null);
        }
    }

    static List<String> listOf(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }
}
