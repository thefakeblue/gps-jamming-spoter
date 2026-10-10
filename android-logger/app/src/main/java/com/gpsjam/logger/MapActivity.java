package com.gpsjam.logger;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Street map of a log, inside the app. It shows the same map page as the website
 * (bundled in the app as assets/map.html) and hands it the log file directly,
 * so nothing is uploaded anywhere. Needs internet for the street tiles.
 */
public class MapActivity extends Activity {

    public static final String EXTRA_FILE = "file";
    // The page runs as if it were on the website, so the street-map server sees a normal web page.
    private static final String BASE = "https://thefakeblue.github.io/gps-jamming-spoter/";
    private static final String LOG_URL = BASE + "__applog.csv";
    // Live airplane (ADS-B) data. The free services don't let web pages read them directly,
    // so the page asks for BASE/__adsb/lat/lon/radius and the app fetches it instead.
    private static final String ADSB_URL = BASE + "__adsb/";
    private static final String[] ADSB_SOURCES = {
        "https://api.adsb.lol/v2/point/%s/%s/%s",
        "https://api.airplanes.live/v2/point/%s/%s/%s",
        "https://opendata.adsb.fi/api/v3/lat/%s/lon/%s/dist/%s",
    };

    private File file;
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String path = getIntent().getStringExtra(EXTRA_FILE);
        file = path != null ? new File(path) : ReadingsActivity.newestLog(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(24), 0, 0);

        LinearLayout bar = new LinearLayout(this);
        Button choose = new Button(this);
        choose.setText("Choose log");
        choose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ReadingsActivity.chooseLog(MapActivity.this, new ReadingsActivity.Picked() {
                    @Override public void onPicked(File f) { file = f; show(); }
                });
            }
        });
        Button reload = new Button(this);
        reload.setText("Refresh");
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { show(); }
        });
        bar.addView(choose, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(reload, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (req.getUrl().toString().startsWith(ADSB_URL)) return fetchAdsb(req.getUrl().toString());
                if (req.getUrl().toString().startsWith(LOG_URL) && file != null && file.exists()) {
                    try {
                        Map<String, String> headers = new HashMap<>();
                        headers.put("Cache-Control", "no-store");
                        return new WebResourceResponse("text/csv", "utf-8", 200, "OK", headers, new FileInputStream(file));
                    } catch (Exception e) {
                        return null;
                    }
                }
                return null;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        show();
    }

    private void show() {
        String html;
        try {
            html = readAsset("map.html");
        } catch (Exception e) {
            html = "<p>Map page missing from the app.</p>";
        }
        String name = file != null ? file.getName().replace("\"", "") : "";
        // Tell the page it is inside the app and which log to fetch.
        String inject = "<script>window.GPSJAM_APP_LOG=\"" + name + "\";</script>";
        int head = html.indexOf("<head>");
        html = head >= 0 ? html.substring(0, head + 6) + inject + html.substring(head + 6) : inject + html;
        web.loadDataWithBaseURL(BASE, html, "text/html", "utf-8", null);
    }

    /** Runs on WebView's background thread, so network is fine here. */
    private WebResourceResponse fetchAdsb(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        String[] p = url.substring(ADSB_URL.length()).split("[/?]");
        if (p.length < 3) return new WebResourceResponse("text/plain", "utf-8", 400, "Bad request", headers, new java.io.ByteArrayInputStream(new byte[0]));
        String lat, lon, r;
        try {
            lat = String.valueOf(Double.parseDouble(p[0]));
            lon = String.valueOf(Double.parseDouble(p[1]));
            r = String.valueOf(Math.max(1, Math.min(250, Integer.parseInt(p[2]))));
        } catch (NumberFormatException e) {
            return new WebResourceResponse("text/plain", "utf-8", 400, "Bad request", headers, new java.io.ByteArrayInputStream(new byte[0]));
        }
        for (String src : ADSB_SOURCES) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(String.format(src, lat, lon, r)).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "GpsJamLogger (github.com/thefakeblue/gps-jamming-spoter)");
                if (c.getResponseCode() != 200) continue;
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                return new WebResourceResponse("application/json", "utf-8", 200, "OK", headers,
                        new java.io.ByteArrayInputStream(out.toByteArray()));
            } catch (Exception e) {
                // try the next free source
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return new WebResourceResponse("text/plain", "utf-8", 502, "No airplane data", headers, new java.io.ByteArrayInputStream(new byte[0]));
    }

    private String readAsset(String name) throws Exception {
        InputStream in = getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
