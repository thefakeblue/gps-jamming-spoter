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
