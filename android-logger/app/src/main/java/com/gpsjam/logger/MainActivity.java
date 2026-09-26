package com.gpsjam.logger;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 1;
    private static final int REQ_NOTIF = 2;

    private TextView statusView, reasonView, statsView, fileView;
    private Button startStop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("GPS Jam Logger");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Records how strong each satellite signal is, second by second, along with where you are. "
                + "Load the saved file into jamming-map.html to see weak spots on a map.");
        intro.setPadding(0, dp(4), 0, dp(12));
        col.addView(intro);

        statusView = new TextView(this);
        statusView.setTextSize(28);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(pad, pad, pad, pad);
        statusView.setTextColor(Color.WHITE);
        col.addView(statusView);

        reasonView = new TextView(this);
        reasonView.setGravity(Gravity.CENTER);
        reasonView.setPadding(0, dp(6), 0, dp(12));
        col.addView(reasonView);

        statsView = new TextView(this);
        statsView.setTypeface(Typeface.MONOSPACE);
        statsView.setTextSize(15);
        col.addView(statsView);

        startStop = new Button(this);
        startStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (LoggerService.latest.running) stopLogging(); else startLogging();
            }
        });
        col.addView(startStop, fullWidth(dp(16)));

        Button save = new Button(this);
        save.setText("Save log to Downloads");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveToDownloads(); }
        });
        col.addView(save, fullWidth(dp(8)));

        Button share = new Button(this);
        share.setText("Share log (email, Drive…)");
        share.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareLog(); }
        });
        col.addView(share, fullWidth(dp(8)));

        fileView = new TextView(this);
        fileView.setPadding(0, dp(8), 0, dp(16));
        col.addView(fileView);

        TextView help = new TextView(this);
        help.setText("What the colours mean\n"
                + "• NORMAL: signals look healthy.\n"
                + "• BLOCKAGE: signals weaker, but it looks like something is in the way (trees, roof, buildings).\n"
                + "• INTERFERENCE: all signals weaker and the radio noise went up. Something nearby may be transmitting.\n"
                + "• JAMMING: big drop on every satellite plus a jump in radio noise.\n"
                + "• NO SIGNAL: no satellites heard at all.\n"
                + "• UNUSUAL: signals are suspiciously strong and identical (possible spoofing).\n\n"
                + "Tips: the first minute outdoors teaches the app what 'normal' looks like for your phone. "
                + "Not every phone reports AGC (the radio-noise gauge); without it, jamming and blockage are harder to tell apart.");
        help.setTextSize(13);
        col.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LoggerService.listener = new LoggerService.Listener() {
            @Override public void onUpdate(final LoggerService.Snapshot s) {
                runOnUiThread(new Runnable() { @Override public void run() { render(s); } });
            }
        };
        render(LoggerService.latest);
    }

    @Override
    protected void onPause() {
        LoggerService.listener = null;
        super.onPause();
    }

    private void render(LoggerService.Snapshot s) {
        startStop.setText(s.running ? "Stop logging" : "Start logging");
        JamDetector.Result r = s.result;
        String status = !s.running ? "NOT LOGGING" : (r == null ? "WAITING…" : r.status.replace('_', ' '));
        statusView.setText(status);
        statusView.setBackgroundColor(colorFor(s.running && r != null ? r.status : ""));
        reasonView.setText(r != null && s.running ? r.reason : "");

        StringBuilder b = new StringBuilder();
        if (r != null) {
            b.append(String.format(Locale.US, "Satellites heard : %d%n", r.sats));
            b.append(String.format(Locale.US, "Top-4 C/N0       : %s dB-Hz%n", num(r.top4)));
            b.append(String.format(Locale.US, "Normal for you   : %s dB-Hz%n", num(r.baseline)));
            b.append(String.format(Locale.US, "AGC (noise gauge): %s%n",
                    !Double.isNaN(r.agc) ? num(r.agc) + " dB (normal " + num(r.agcBaseline) + ")"
                            : (s.agcSupported ? "waiting" : "not reported by this phone")));
        }
        Location l = s.location;
        if (l != null) {
            b.append(String.format(Locale.US, "Position         : %.5f, %.5f%n", l.getLatitude(), l.getLongitude()));
            b.append(String.format(Locale.US, "  from %s, ±%.0f m%n", s.locSource, l.getAccuracy()));
        } else if (s.running) {
            b.append("Position         : waiting for a fix\n");
        }
        if (s.running || s.epochs > 0) {
            b.append(String.format(Locale.US, "Seconds logged   : %d  (%d rows)%n", s.epochs, s.rows));
        }
        if (s.running && !s.measurementsSupported) {
            b.append("\nThis phone doesn't share raw GNSS measurements, so AGC is unavailable.\n");
        }
        if (s.error != null) b.append("\nError: ").append(s.error).append('\n');
        statsView.setText(b.toString());

        File f = s.file != null ? s.file : newestLog();
        fileView.setText(f != null ? "Log file: " + f.getName() : "No log yet.");
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "–" : String.format(Locale.US, "%.1f", v);
    }

    private static int colorFor(String status) {
        if (JamDetector.NORMAL.equals(status)) return Color.rgb(46, 125, 50);
        if (JamDetector.BLOCKAGE.equals(status)) return Color.rgb(158, 118, 0);
        if (JamDetector.INTERFERENCE.equals(status)) return Color.rgb(230, 81, 0);
        if (JamDetector.JAMMING.equals(status)) return Color.rgb(198, 40, 40);
        if (JamDetector.NO_SIGNAL.equals(status)) return Color.rgb(69, 69, 69);
        if (JamDetector.UNUSUAL.equals(status)) return Color.rgb(106, 27, 154);
        return Color.rgb(97, 97, 97);
    }

    // ---- start / stop -------------------------------------------------------

    private void startLogging() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            // Asked once; logging works either way, the notification just may be hidden.
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            return;
        }
        Intent i = new Intent(this, LoggerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void stopLogging() {
        stopService(new Intent(this, LoggerService.class));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_LOCATION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startLogging();
            else toast("Precise location is needed to read satellite signals.");
        } else if (requestCode == REQ_NOTIF) {
            Intent i = new Intent(this, LoggerService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
    }

    // ---- export ---------------------------------------------------------------

    private File currentLog() {
        File f = LoggerService.latest.file;
        if (f == null || !f.exists()) f = newestLog();
        if (f == null) toast("No log yet. Tap Start logging first.");
        return f;
    }

    private File newestLog() {
        File[] files = LoggerService.logDir(this).listFiles();
        File best = null;
        if (files != null) for (File f : files) if (best == null || f.lastModified() > best.lastModified()) best = f;
        return best;
    }

    private void saveToDownloads() {
        File f = currentLog();
        if (f == null) return;
        if (Build.VERSION.SDK_INT < 29) {
            toast("On this Android version, use Share instead.");
            return;
        }
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/GpsJamLogger");
            ContentResolver cr = getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("could not create file");
            InputStream in = new FileInputStream(f);
            OutputStream out = cr.openOutputStream(uri);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            toast("Saved to Downloads/GpsJamLogger/" + f.getName());
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }

    private void shareLog() {
        File f = currentLog();
        if (f == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/csv");
        send.putExtra(Intent.EXTRA_STREAM, LogFileProvider.uriFor(f));
        send.putExtra(Intent.EXTRA_SUBJECT, "GPS log " + f.getName());
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share GPS log"));
    }

    // ---- helpers ----------------------------------------------------------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams fullWidth(int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = topMargin;
        return p;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
