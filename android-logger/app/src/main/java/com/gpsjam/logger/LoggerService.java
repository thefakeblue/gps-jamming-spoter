package com.gpsjam.logger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs in the background (with a notification) and writes one CSV row per
 * satellite per second: position, C/N0, AGC, elevation, and the live verdict.
 */
public class LoggerService extends Service {

    public static final String ACTION_STOP = "com.gpsjam.logger.STOP";
    private static final String CHANNEL_ID = "logging";
    private static final int NOTIF_ID = 1;

    public static final String CSV_HEADER =
            "utc_ms,local_time,lat,lon,alt_m,acc_m,speed_mps,loc_source,loc_age_s,"
            + "constellation,svid,band,freq_mhz,cn0_dbhz,agc_db,elev_deg,azim_deg,used_in_fix,"
            + "epoch_sats,epoch_top4_cn0,epoch_status";

    /** Live snapshot the screen reads. */
    public static class Snapshot {
        public boolean running;
        public JamDetector.Result result;
        public Location location;
        public String locSource = "";
        public long rows;
        public long epochs;
        public boolean agcSupported;
        public boolean measurementsSupported;
        public File file;
        public String error;
    }

    public interface Listener { void onUpdate(Snapshot s); }

    // Simple static hand-off to the Activity (both live in the same process).
    static volatile Snapshot latest = new Snapshot();
    static volatile Listener listener;
    static File lastFile;

    private LocationManager lm;
    private Handler handler;
    private BufferedWriter out;
    private PowerManager.WakeLock wakeLock;
    private final JamDetector detector = new JamDetector();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);

    private Location gpsLoc, netLoc;
    private long gpsLocAt, netLocAt; // elapsedRealtime when received
    private long lastStatusAt;
    private final Map<String, Double> agcBySat = new HashMap<>();  // "const:svid:band" -> dB
    private final Map<String, Double> agcByBand = new HashMap<>(); // "const:band" or "*:band" -> dB
    private long agcAt;
    private Snapshot snap = new Snapshot();

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (snap.running) return START_STICKY;
        startInForeground();
        handler = new Handler(Looper.getMainLooper());
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            openFile();
            startListening();
            snap.running = true;
            handler.postDelayed(watchdog, 2000);
        } catch (Exception e) {
            snap.error = e.getMessage();
            publish();
            stopSelf();
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpsjam:logging");
        wakeLock.acquire(12 * 60 * 60 * 1000L);
        publish();
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Logging", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, LoggerService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        b.setContentTitle("Logging GPS signal quality")
                .setContentText("Tap to open. Your route and signal levels are being saved.")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void openFile() throws IOException {
        File dir = logDir(this);
        String name = "gnss_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
        File f = new File(dir, name);
        out = new BufferedWriter(new FileWriter(f));
        out.write(CSV_HEADER);
        out.write("\n");
        snap.file = f;
        lastFile = f;
    }

    static File logDir(Context c) {
        File dir = new File(c.getFilesDir(), "logs");
        dir.mkdirs();
        return dir;
    }

    @SuppressWarnings("MissingPermission")
    private void startListening() {
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener, Looper.getMainLooper());
        // Network (Wi-Fi / cell) location keeps the map useful when a jammer kills the GPS fix.
        if (lm.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 0, netListener, Looper.getMainLooper());
            } catch (Exception ignored) { }
        }
        lm.registerGnssStatusCallback(statusCallback, handler);
        snap.measurementsSupported = lm.registerGnssMeasurementsCallback(measCallback, handler);
    }

    private final LocationListener gpsListener = new SimpleLocationListener() {
        @Override public void onLocationChanged(Location l) { gpsLoc = l; gpsLocAt = SystemClock.elapsedRealtime(); }
    };
    private final LocationListener netListener = new SimpleLocationListener() {
        @Override public void onLocationChanged(Location l) { netLoc = l; netLocAt = SystemClock.elapsedRealtime(); }
    };

    private final GnssStatus.Callback statusCallback = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) { writeEpoch(status); }
    };

    private final GnssMeasurementsEvent.Callback measCallback = new GnssMeasurementsEvent.Callback() {
        @Override public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) { readAgc(event); }
    };

    /** If satellites vanish completely some phones stop sending status; still log an empty second. */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!snap.running) return;
            if (SystemClock.elapsedRealtime() - lastStatusAt > 2500) writeEpoch(null);
            handler.postDelayed(this, 1000);
        }
    };

    private void readAgc(GnssMeasurementsEvent event) {
        agcBySat.clear();
        agcByBand.clear();
        // Android 7-12: AGC is reported on each measurement.
        for (GnssMeasurement m : event.getMeasurements()) {
            if (Build.VERSION.SDK_INT >= 26 && m.hasAutomaticGainControlLevelDb()) {
                String band = band(m.hasCarrierFrequencyHz() ? m.getCarrierFrequencyHz() : 0f);
                double agc = m.getAutomaticGainControlLevelDb();
                agcBySat.put(m.getConstellationType() + ":" + m.getSvid() + ":" + band, agc);
                agcByBand.put(m.getConstellationType() + ":" + band, agc);
                agcByBand.put("*:" + band, agc);
            }
        }
        // Android 13+: AGC moved to the event (read by reflection so this also builds against older SDKs).
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Method get = event.getClass().getMethod("getGnssAutomaticGainControls");
                Collection<?> agcs = (Collection<?>) get.invoke(event);
                for (Object o : agcs) {
                    double level = (Double) o.getClass().getMethod("getLevelDb").invoke(o);
                    int cons = (Integer) o.getClass().getMethod("getConstellationType").invoke(o);
                    long freq = (Long) o.getClass().getMethod("getCarrierFrequencyHz").invoke(o);
                    String band = band(freq);
                    agcByBand.put(cons + ":" + band, level);
                    agcByBand.put("*:" + band, level);
                }
            } catch (Exception ignored) { }
        }
        if (!agcByBand.isEmpty()) {
            agcAt = SystemClock.elapsedRealtime();
            snap.agcSupported = true;
        }
    }

    private void writeEpoch(GnssStatus status) {
        if (out == null) return;
        long now = SystemClock.elapsedRealtime();
        lastStatusAt = status != null ? now : lastStatusAt;
        long utc = System.currentTimeMillis();
        boolean agcFresh = now - agcAt < 3000;

        // Pick the freshest good location: GPS if recent, else network.
        Location loc = null; String src = ""; long age = 0;
        if (gpsLoc != null && now - gpsLocAt < 5000) { loc = gpsLoc; src = "gps"; age = now - gpsLocAt; }
        else if (netLoc != null && now - netLocAt < 60000) { loc = netLoc; src = "network"; age = now - netLocAt; }
        else if (gpsLoc != null && now - gpsLocAt < 30000) { loc = gpsLoc; src = "gps_stale"; age = now - gpsLocAt; }

        // Gather satellites.
        int n = status == null ? 0 : status.getSatelliteCount();
        List<JamDetector.Sat> sats = new ArrayList<>();
        String[] rows = new String[n];
        for (int i = 0; i < n; i++) {
            float cn0 = status.getCn0DbHz(i);
            int cons = status.getConstellationType(i);
            int svid = status.getSvid(i);
            float freq = (Build.VERSION.SDK_INT >= 26 && status.hasCarrierFrequencyHz(i)) ? status.getCarrierFrequencyHz(i) : 0f;
            String band = band(freq);
            Double agc = null;
            if (agcFresh) {
                agc = agcBySat.get(cons + ":" + svid + ":" + band);
                if (agc == null) agc = agcByBand.get(cons + ":" + band);
                if (agc == null) agc = agcByBand.get("*:" + band);
            }
            if (cn0 > 0) {
                sats.add(new JamDetector.Sat(cn0, status.getElevationDegrees(i)));
            }
            rows[i] = constellationName(cons) + "," + svid + "," + band + ","
                    + (freq > 0 ? String.format(Locale.US, "%.3f", freq / 1e6) : "") + ","
                    + String.format(Locale.US, "%.1f", cn0) + ","
                    + (agc != null ? String.format(Locale.US, "%.2f", agc) : "") + ","
                    + String.format(Locale.US, "%.1f", status.getElevationDegrees(i)) + ","
                    + String.format(Locale.US, "%.1f", status.getAzimuthDegrees(i)) + ","
                    + (status.usedInFix(i) ? 1 : 0);
        }
        // Each constellation has its own AGC level (GPS and BeiDou differ by ~8 dB on some phones),
        // so averaging them jumps around as satellites come and go. Use one steady reference: GPS L1 first.
        double epochAgc = Double.NaN;
        if (agcFresh) {
            int[] order = {GnssStatus.CONSTELLATION_GPS, GnssStatus.CONSTELLATION_GALILEO,
                    GnssStatus.CONSTELLATION_GLONASS, GnssStatus.CONSTELLATION_BEIDOU};
            for (int c : order) {
                Double v = agcByBand.get(c + ":L1");
                if (v != null) { epochAgc = v; break; }
            }
            if (Double.isNaN(epochAgc) && agcByBand.get("*:L1") != null) epochAgc = agcByBand.get("*:L1");
        }
        JamDetector.Result r = detector.evaluate(sats, epochAgc);

        String prefix = utc + "," + timeFmt.format(new Date(utc)) + ","
                + (loc != null ? String.format(Locale.US, "%.7f,%.7f,%.1f,%.1f,%.2f",
                    loc.getLatitude(), loc.getLongitude(), loc.getAltitude(), loc.getAccuracy(), loc.getSpeed())
                    : ",,,,")
                + "," + src + "," + (loc != null ? String.format(Locale.US, "%.1f", age / 1000.0) : "") + ",";
        String suffix = "," + r.sats + "," + (Double.isNaN(r.top4) ? "" : String.format(Locale.US, "%.1f", r.top4))
                + "," + r.status + "\n";
        try {
            if (n == 0) {
                // One row with no satellite so the map still sees this second.
                out.write(prefix + ",,,,,"
                        + (Double.isNaN(epochAgc) ? "" : String.format(Locale.US, "%.2f", epochAgc))
                        + ",,," + suffix);
                snap.rows++;
            } else {
                for (String row : rows) { out.write(prefix + row + suffix); snap.rows++; }
            }
            if (snap.epochs % 10 == 0) out.flush();
        } catch (IOException e) {
            snap.error = "Could not write file: " + e.getMessage();
        }
        snap.epochs++;
        snap.result = r;
        snap.location = loc;
        snap.locSource = src;
        publish();
    }

    static String band(double hz) {
        if (hz <= 0) return "L1"; // older phones don't report frequency; they are L1-only
        double mhz = hz / 1e6;
        if (mhz >= 1500) return "L1";
        if (mhz >= 1215) return "L2";
        if (mhz >= 1190) return "E5b";
        return "L5";
    }

    static String constellationName(int t) {
        switch (t) {
            case GnssStatus.CONSTELLATION_GPS: return "GPS";
            case GnssStatus.CONSTELLATION_SBAS: return "SBAS";
            case GnssStatus.CONSTELLATION_GLONASS: return "GLONASS";
            case GnssStatus.CONSTELLATION_QZSS: return "QZSS";
            case GnssStatus.CONSTELLATION_BEIDOU: return "BEIDOU";
            case GnssStatus.CONSTELLATION_GALILEO: return "GALILEO";
            case 7: return "NAVIC"; // GnssStatus.CONSTELLATION_IRNSS
            default: return "UNKNOWN";
        }
    }

    private void publish() {
        Snapshot s = new Snapshot();
        s.running = snap.running; s.result = snap.result; s.location = snap.location;
        s.locSource = snap.locSource; s.rows = snap.rows; s.epochs = snap.epochs;
        s.agcSupported = snap.agcSupported; s.measurementsSupported = snap.measurementsSupported;
        s.file = snap.file; s.error = snap.error;
        latest = s;
        Listener l = listener;
        if (l != null) l.onUpdate(s);
    }

    @Override
    public void onDestroy() {
        snap.running = false;
        if (handler != null) handler.removeCallbacks(watchdog);
        if (lm != null) {
            lm.removeUpdates(gpsListener);
            lm.removeUpdates(netListener);
            lm.unregisterGnssStatusCallback(statusCallback);
            lm.unregisterGnssMeasurementsCallback(measCallback);
        }
        try { if (out != null) out.close(); } catch (IOException ignored) { }
        out = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        publish();
        super.onDestroy();
    }

    /** LocationListener with the old optional methods filled in (needed on Android 7-10). */
    abstract static class SimpleLocationListener implements LocationListener {
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    }
}
