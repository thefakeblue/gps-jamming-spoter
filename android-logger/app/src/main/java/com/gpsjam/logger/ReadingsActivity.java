package com.gpsjam.logger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Shows a saved log in plain words: a summary, every satellite in the latest second, and each second. */
public class ReadingsActivity extends Activity {

    public static final String EXTRA_FILE = "file";
    private static final int SHOW_SECONDS = 300;

    private File file;
    private LinearLayout content;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            load();
            if (isLive()) handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String path = getIntent().getStringExtra(EXTRA_FILE);
        file = path != null ? new File(path) : newestLog(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, dp(24), pad, 0);

        LinearLayout bar = new LinearLayout(this);
        Button choose = new Button(this);
        choose.setText("Choose log");
        choose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                chooseLog(ReadingsActivity.this, new Picked() {
                    @Override public void onPicked(File f) { file = f; restart(); }
                });
            }
        });
        Button map = new Button(this);
        map.setText("Show on map");
        map.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (file == null) return;
                startActivity(new Intent(ReadingsActivity.this, MapActivity.class)
                        .putExtra(MapActivity.EXTRA_FILE, file.getAbsolutePath()));
            }
        });
        bar.addView(choose, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(map, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); restart(); }
    @Override protected void onPause() { handler.removeCallbacks(refresher); super.onPause(); }

    private void restart() {
        handler.removeCallbacks(refresher);
        refresher.run();
    }

    private boolean isLive() {
        LoggerService.Snapshot s = LoggerService.latest;
        return s.running && s.file != null && file != null && s.file.getAbsolutePath().equals(file.getAbsolutePath());
    }

    private void load() {
        content.removeAllViews();
        if (file == null || !file.exists()) {
            content.addView(text("No log yet. Go back and tap Start logging.", 15, false));
            return;
        }
        LogReader.Summary s;
        try {
            s = LogReader.read(file, SHOW_SECONDS);
        } catch (Exception e) {
            content.addView(text("Could not read the log: " + e.getMessage(), 15, false));
            return;
        }

        // ---- Summary
        TextView title = text(file.getName() + (isLive() ? "  (recording now)" : ""), 18, true);
        content.addView(title);
        long mins = (s.lastT - s.firstT) / 60000;
        StringBuilder sum = new StringBuilder();
        sum.append(String.format(Locale.US, "%d seconds recorded (%d min)%n", s.seconds, mins));
        if (!Double.isNaN(s.minTop4)) {
            sum.append(String.format(Locale.US, "Signal (top-4 C/N0): %.1f to %.1f dB-Hz%n", s.minTop4, s.maxTop4));
        }
        sum.append(s.hasAgc ? "AGC noise gauge: recorded\n" : "AGC noise gauge: not reported by this phone\n");
        content.addView(text(sum.toString(), 14, false));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        String[] order = {JamDetector.JAMMING, JamDetector.INTERFERENCE, JamDetector.BLOCKAGE,
                JamDetector.NO_SIGNAL, JamDetector.UNUSUAL, JamDetector.NORMAL};
        for (String st : order) {
            Integer n = s.statusCounts.get(st);
            if (n == null) continue;
            chips.addView(statusRow(st, String.format(Locale.US, "%d s (%.0f%%)", n, 100.0 * n / s.seconds)));
        }
        content.addView(chips);

        if (s.recent.isEmpty()) return;
        LogReader.Epoch last = s.recent.getLast();

        // ---- Latest second, satellite by satellite
        content.addView(heading("Satellites at " + clock(last.time)));
        content.addView(text(satNote(last), 13, false));
        List<LogReader.Sat> sats = new ArrayList<>(last.satList);
        Collections.sort(sats, new Comparator<LogReader.Sat>() {
            @Override public int compare(LogReader.Sat a, LogReader.Sat b) { return Double.compare(b.cn0, a.cn0); }
        });
        for (LogReader.Sat sat : sats) content.addView(satRow(sat));
        if (sats.isEmpty()) content.addView(text("No satellites heard in this second.", 14, false));

        // ---- Second by second
        content.addView(heading("Second by second (newest first)"));
        content.addView(text("time      verdict        signal  sats  AGC", 12, false, true));
        Iterator<LogReader.Epoch> it = s.recent.descendingIterator();
        while (it.hasNext()) content.addView(secondRow(it.next()));
        if (s.seconds > SHOW_SECONDS) {
            content.addView(text("Showing the last " + SHOW_SECONDS + " seconds. Show on map has the whole log.", 12, false));
        }
    }

    private String satNote(LogReader.Epoch e) {
        StringBuilder b = new StringBuilder();
        b.append(e.sats).append(" satellites heard");
        if (!Double.isNaN(e.lat)) {
            b.append(String.format(Locale.US, " at %.5f, %.5f (%s", e.lat, e.lon, e.src));
            if (!Double.isNaN(e.acc)) b.append(String.format(Locale.US, ", ±%.0f m", e.acc));
            b.append(")");
        }
        b.append(". Bars show C/N0: longer is stronger, 40+ is healthy. ✓ = used for your position.");
        return b.toString();
    }

    // ---- row builders -------------------------------------------------------

    private View satRow(LogReader.Sat sat) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));
        TextView name = text(String.format(Locale.US, "%-7s %3d %s", shortCons(sat.cons), sat.svid, sat.band), 13, false, true);
        row.addView(name, new LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT));

        double frac = Double.isNaN(sat.cn0) ? 0 : Math.max(0, Math.min(1, sat.cn0 / 55.0));
        LinearLayout track = new LinearLayout(this);
        track.setBackgroundColor(Color.argb(40, 128, 128, 128));
        View fill = new View(this);
        fill.setBackgroundColor(cn0Color(sat.cn0));
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(12), (float) frac));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, dp(12), (float) (1 - frac)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(12), 1);
        tp.rightMargin = dp(8);
        row.addView(track, tp);

        TextView val = text(String.format(Locale.US, "%4.1f %s", sat.cn0, sat.used ? "✓" : " "), 13, false, true);
        row.addView(val, new LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View secondRow(LogReader.Epoch e) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(text(clock(e.time) + "  ", 13, false, true));
        row.addView(pill(e.status));
        String rest = String.format(Locale.US, "  %5s  %4d  %s",
                Double.isNaN(e.top4) ? "–" : String.format(Locale.US, "%.1f", e.top4), e.sats,
                Double.isNaN(e.agc) ? "–" : String.format(Locale.US, "%.1f", e.agc));
        row.addView(text(rest, 13, false, true));
        return row;
    }

    private View statusRow(String status, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(pill(status));
        row.addView(text("  " + detail, 14, false));
        return row;
    }

    private TextView pill(String status) {
        TextView t = text(label(status), 12, true);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(1), dp(6), dp(1));
        t.setMinWidth(dp(104));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MainActivity.colorFor(status));
        bg.setCornerRadius(dp(10));
        t.setBackground(bg);
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s, 16, true);
        t.setPadding(0, dp(18), 0, dp(4));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) { return text(s, sp, bold, false); }

    private TextView text(String s, int sp, boolean bold, boolean mono) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (mono) t.setTypeface(bold ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) : Typeface.MONOSPACE);
        else if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static String label(String status) {
        if (JamDetector.NO_SIGNAL.equals(status)) return "NO SIGNAL";
        return status.isEmpty() ? "?" : status;
    }

    static String clock(String localTime) {
        // 2026-09-25T22:43:30.794-0600 -> 22:43:30
        return localTime != null && localTime.length() >= 19 ? localTime.substring(11, 19) : "--:--:--";
    }

    static String shortCons(String c) {
        if ("GALILEO".equals(c)) return "Galileo";
        if ("GLONASS".equals(c)) return "GLONASS";
        if ("BEIDOU".equals(c)) return "BeiDou";
        return c;
    }

    static int cn0Color(double v) {
        if (Double.isNaN(v) || v <= 0) return Color.GRAY;
        float t = (float) Math.max(0, Math.min(1, (v - 20) / 25.0));
        return Color.HSVToColor(new float[]{t * 120f, 0.75f, 0.75f});
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ---- log picking (shared with MapActivity) ------------------------------

    interface Picked { void onPicked(File f); }

    static File[] logsNewestFirst(Activity a) {
        File[] files = LoggerService.logDir(a).listFiles();
        if (files == null) return new File[0];
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File x, File y) { return Long.compare(y.lastModified(), x.lastModified()); }
        });
        return files;
    }

    static File newestLog(Activity a) {
        File[] f = logsNewestFirst(a);
        return f.length > 0 ? f[0] : null;
    }

    static void chooseLog(Activity a, final Picked cb) {
        final File[] files = logsNewestFirst(a);
        if (files.length == 0) return;
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getName() + String.format(Locale.US, "  (%d KB)", files[i].length() / 1024);
        }
        new AlertDialog.Builder(a)
                .setTitle("Choose a log")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { cb.onPicked(files[which]); }
                })
                .show();
    }
}
