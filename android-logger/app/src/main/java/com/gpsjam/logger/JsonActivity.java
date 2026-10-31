package com.gpsjam.logger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Shows the newest readings as raw JSON, one object per satellite per second,
 * with the same field names the GPSI app uses plus our extra fields.
 */
public class JsonActivity extends Activity {

    public static final String EXTRA_FILE = "file";
    private static final int SECONDS_SHOWN = 5;

    private File file;
    private TextView header, body;
    private String lastJson = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            load();
            if (isLive()) handler.postDelayed(this, 2000);
        }
    };
    private final SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        String path = getIntent().getStringExtra(EXTRA_FILE);
        file = path != null ? new File(path) : ReadingsActivity.newestLog(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, dp(24), pad, 0);

        LinearLayout bar = new LinearLayout(this);
        bar.addView(button("Choose log", new View.OnClickListener() {
            @Override public void onClick(View v) {
                ReadingsActivity.chooseLog(JsonActivity.this, new ReadingsActivity.Picked() {
                    @Override public void onPicked(File f) { file = f; restart(); }
                });
            }
        }), weight());
        bar.addView(button("Copy", new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("GPS readings", lastJson));
                Toast.makeText(JsonActivity.this, "Copied", Toast.LENGTH_SHORT).show();
            }
        }), weight());
        bar.addView(button("Street map", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (file == null) return;
                startActivity(new Intent(JsonActivity.this, MapActivity.class)
                        .putExtra(MapActivity.EXTRA_FILE, file.getAbsolutePath()));
            }
        }), weight());
        root.addView(bar);

        header = new TextView(this);
        header.setPadding(0, dp(6), 0, dp(6));
        root.addView(header);

        body = new TextView(this);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(12);
        body.setTextIsSelectable(true);
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.addView(body);
        ScrollView v = new ScrollView(this);
        v.addView(h);
        root.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
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
        if (file == null || !file.exists()) {
            header.setText("No log yet. Go back and tap Start logging.");
            body.setText("");
            return;
        }
        try {
            LogReader.Summary s = LogReader.read(file, SECONDS_SHOWN);
            JSONArray arr = new JSONArray();
            Iterator<LogReader.Epoch> it = s.recent.descendingIterator();
            while (it.hasNext()) {
                LogReader.Epoch e = it.next();
                String stamp = utc.format(new Date(e.t));
                if (e.satList.isEmpty()) arr.put(entry(e, null, stamp));
                for (LogReader.Sat sat : e.satList) arr.put(entry(e, sat, stamp));
            }
            lastJson = arr.toString(2);
            body.setText(lastJson);
            header.setText(file.getName() + (isLive() ? " · recording, updates every 2 s" : "")
                    + "\nNewest " + Math.min(SECONDS_SHOWN, s.seconds) + " seconds of " + s.seconds
                    + ", newest first. One { } block per satellite per second.");
        } catch (Exception ex) {
            header.setText("Could not read the log: " + ex.getMessage());
        }
    }

    private JSONObject entry(LogReader.Epoch e, LogReader.Sat sat, String stamp) throws Exception {
        JSONObject o = new JSONObject();
        o.put("Latitude", n(e.lat));
        o.put("Longitude", n(e.lon));
        o.put("Altitude", n(e.alt));
        o.put("Device", Build.MODEL);
        o.put("Constellation", sat != null ? sat.cons : JSONObject.NULL);
        o.put("Vehicle", sat != null ? (Object) sat.svid : JSONObject.NULL);
        o.put("Band", sat != null ? sat.band : JSONObject.NULL);
        o.put("SignalToNoise", sat != null ? n(sat.cn0) : JSONObject.NULL);
        o.put("AGC", n(sat != null && !Double.isNaN(sat.agc) ? sat.agc : e.agc));
        o.put("Elevation", sat != null ? n(sat.elev) : JSONObject.NULL);
        o.put("Azimuth", sat != null ? n(sat.azim) : JSONObject.NULL);
        o.put("UsedInFix", sat != null ? (Object) sat.used : JSONObject.NULL);
        o.put("SatCount", e.sats);
        o.put("Status", e.status);
        o.put("Mode", e.mode);
        o.put("PositionSource", e.src);
        o.put("TimeStamp", stamp);
        return o;
    }

    private static Object n(double v) { return Double.isNaN(v) ? JSONObject.NULL : (Object) v; }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
