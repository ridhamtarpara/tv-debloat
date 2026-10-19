package com.calm.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Html;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Nature wallpapers: cached daily picks from Wikimedia Commons featured pictures (freely licensed,
 * no API key), with a few bundled fallbacks in assets/wallpapers. The only hosts contacted are
 * commons.wikimedia.org (API) and *.wikimedia.org (image thumbnails).
 */
final class Wallpapers {
    private static final String TAG = "CalmWallpapers";
    private static final String API = "https://commons.wikimedia.org/w/api.php";
    private static final String UA = "CalmLauncher/1.0 (personal Android TV home screen)";
    private static final String[] CATEGORIES = {
        "Featured_pictures_of_mountains", "Featured_pictures_of_lakes", "Featured_pictures_of_forests",
        "Featured_pictures_of_waterfalls", "Featured_pictures_of_beaches", "Featured_pictures_of_coasts",
    };
    private static final int KEEP = 15;
    static final String ASSET = "asset:";

    private Wallpapers() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "wallpapers");
        d.mkdirs();
        return d;
    }

    /** Newest downloads first; bundled images only when nothing has been downloaded yet. */
    static List<String> list(Context c) {
        List<String> out = new ArrayList<>();
        File[] files = dir(c).listFiles((d, n) -> n.endsWith(".jpg"));
        if (files != null && files.length > 0) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) out.add(f.getAbsolutePath());
        }
        if (out.size() < 3) {
            try {
                for (String n : c.getAssets().list("wallpapers")) {
                    if (n.endsWith(".jpg")) out.add(ASSET + "wallpapers/" + n);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    static Bitmap decode(Context c, String src, int reqW, int reqH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            decodeStream(c, src, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory; photos look the same at TV distance
            return decodeStream(c, src, o);
        } catch (Exception e) {
            Log.w(TAG, "decode " + src, e);
            return null;
        }
    }

    private static Bitmap decodeStream(Context c, String src, BitmapFactory.Options o) throws Exception {
        try (InputStream in = open(c, src)) {
            return BitmapFactory.decodeStream(in, null, o);
        }
    }

    private static InputStream open(Context c, String src) throws Exception {
        if (src.startsWith(ASSET)) return c.getAssets().open(src.substring(ASSET.length()));
        return new java.io.FileInputStream(src);
    }

    static String credit(Context c, String src) {
        String side = src.replaceAll("\\.jpg$", ".txt");
        try (InputStream in = open(c, side)) {
            return new String(readAll(in), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ---- daily fetch (runs on a background thread from WallpaperJob) ----

    private static final java.util.concurrent.locks.ReentrantLock RUNNING = new java.util.concurrent.locks.ReentrantLock();
    private static final long PAUSE_MS = 1500; // be gentle with the Wikimedia API

    /** Returns images downloaded, or -1 if rate limited (caller should back off). */
    static int fetch(Context c, int count) {
        if (!RUNNING.tryLock()) return 0; // another job is already fetching
        try {
            return fetchLocked(c, count);
        } finally {
            RUNNING.unlock();
        }
    }

    private static int fetchLocked(Context c, int count) {
        Random rnd = new Random();
        File dir = dir(c);
        java.util.Map<String, JSONArray> lists = new java.util.HashMap<>();
        int got = 0;
        for (int attempt = 0; attempt < count * 6 && got < count; attempt++) {
            try {
                String cat = CATEGORIES[rnd.nextInt(CATEGORIES.length)];
                JSONArray members = lists.get(cat);
                if (members == null) {
                    members = getJson(API + "?action=query&format=json&list=categorymembers&cmtype=file&cmlimit=500"
                                    + "&cmtitle=Category:" + cat)
                            .getJSONObject("query").getJSONArray("categorymembers");
                    lists.put(cat, members);
                    Thread.sleep(PAUSE_MS);
                }
                if (members.length() == 0) continue;
                String title = members.getJSONObject(rnd.nextInt(members.length())).getString("title");
                String id = Integer.toHexString(title.hashCode());
                File jpg = new File(dir, id + ".jpg");
                if (jpg.exists()) continue;

                JSONObject pages = getJson(API + "?action=query&format=json&prop=imageinfo&iiprop=url|size|extmetadata"
                                + "&iiurlwidth=1920&iiextmetadatafilter=Artist|LicenseShortName|ObjectName"
                                + "&titles=" + URLEncoder.encode(title, "UTF-8"))
                        .getJSONObject("query").getJSONObject("pages");
                Thread.sleep(PAUSE_MS);
                JSONObject info = pages.getJSONObject(pages.keys().next()).getJSONArray("imageinfo").getJSONObject(0);
                int w = info.getInt("width"), h = info.getInt("height");
                double ratio = (double) w / h;
                if (w < 1920 || ratio < 1.3 || ratio > 2.1) continue; // landscape, TV-ish shapes only
                String thumb = info.getString("thumburl");
                String host = new URL(thumb).getHost();
                if (!host.endsWith(".wikimedia.org")) continue;

                File tmp = new File(dir, id + ".part");
                try (InputStream in = httpGet(thumb); OutputStream out = new FileOutputStream(tmp)) {
                    copy(in, out);
                }
                JSONObject m = info.optJSONObject("extmetadata");
                String name = meta(m, "ObjectName");
                if (name.isEmpty()) name = title.replaceFirst("^File:", "").replaceAll("\\.\\w+$", "");
                String credit = name + "  ·  " + meta(m, "Artist") + "  ·  " + meta(m, "LicenseShortName")
                        + " via Wikimedia Commons";
                try (OutputStream out = new FileOutputStream(new File(dir, id + ".txt"))) {
                    out.write(credit.getBytes(StandardCharsets.UTF_8));
                }
                if (tmp.renameTo(jpg)) got++;
            } catch (RateLimited e) {
                Log.w(TAG, "rate limited by Wikimedia; backing off");
                prune(dir);
                return got > 0 ? got : -1;
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.w(TAG, "fetch attempt failed: " + e);
            }
        }
        prune(dir);
        Log.i(TAG, "fetched " + got + " wallpapers");
        return got;
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (files == null || files.length <= KEEP) return;
        List<File> l = new ArrayList<>(Arrays.asList(files));
        Collections.sort(l, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : l.subList(KEEP, l.size())) {
            f.delete();
            new File(f.getPath().replaceAll("\\.jpg$", ".txt")).delete();
        }
        File[] parts = dir.listFiles((d, n) -> n.endsWith(".part"));
        if (parts != null) for (File p : parts) p.delete();
    }

    private static String meta(JSONObject m, String key) {
        if (m == null) return "";
        JSONObject o = m.optJSONObject(key);
        if (o == null) return "";
        return Html.fromHtml(o.optString("value"), Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
    }

    private static JSONObject getJson(String url) throws Exception {
        try (InputStream in = httpGet(url)) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        }
    }

    private static InputStream httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", UA);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code == 429 || code == 503) throw new RateLimited();
        if (code != 200) throw new java.io.IOException("HTTP " + code + " " + url);
        return conn.getInputStream();
    }

    private static final class RateLimited extends java.io.IOException {}

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
