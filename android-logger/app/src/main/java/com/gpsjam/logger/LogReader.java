package com.gpsjam.logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/** Reads a GPS Jam Logger CSV back into one entry per second, for the readings screen. */
public class LogReader {

    public static class Sat {
        public String cons, band;
        public int svid;
        public double cn0, elev, azim, agc = Double.NaN;
        public boolean used;
    }

    public static class Epoch {
        public long t;
        public String time = "";
        public double lat = Double.NaN, lon = Double.NaN, acc = Double.NaN, alt = Double.NaN;
        public String src = "";
        public String status = "";
        public double top4 = Double.NaN;
        public int sats;
        public double agc = Double.NaN; // GPS L1 AGC when present (same reference the detector uses)
        public final List<Sat> satList = new ArrayList<>();
    }

    public static class Summary {
        public File file;
        public int seconds;
        public long firstT, lastT;
        public final Map<String, Integer> statusCounts = new HashMap<>();
        public boolean hasAgc;
        public double minTop4 = Double.NaN, maxTop4 = Double.NaN;
        /** Newest seconds last; capped so huge logs stay quick to show. */
        public final LinkedList<Epoch> recent = new LinkedList<>();
    }

    public static Summary read(File f, int keepRecent) throws IOException {
        Summary s = new Summary();
        s.file = f;
        BufferedReader in = new BufferedReader(new FileReader(f));
        try {
            String header = in.readLine();
            if (header == null) return s;
            Map<String, Integer> idx = new HashMap<>();
            String[] h = header.split(",");
            for (int i = 0; i < h.length; i++) idx.put(h[i].trim(), i);
            Epoch cur = null;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] c = line.split(",", -1);
                long t;
                try { t = Long.parseLong(get(c, idx, "utc_ms")); } catch (NumberFormatException e) { continue; }
                if (cur == null || cur.t != t) {
                    if (cur != null) finish(s, cur, keepRecent);
                    cur = new Epoch();
                    cur.t = t;
                    cur.time = get(c, idx, "local_time");
                    cur.lat = num(get(c, idx, "lat"));
                    cur.lon = num(get(c, idx, "lon"));
                    cur.acc = num(get(c, idx, "acc_m"));
                    cur.alt = num(get(c, idx, "alt_m"));
                    cur.src = get(c, idx, "loc_source");
                    cur.status = get(c, idx, "epoch_status");
                    cur.top4 = num(get(c, idx, "epoch_top4_cn0"));
                    try { cur.sats = Integer.parseInt(get(c, idx, "epoch_sats")); } catch (NumberFormatException e) { cur.sats = 0; }
                }
                String cons = get(c, idx, "constellation");
                double agc = num(get(c, idx, "agc_db"));
                String band = get(c, idx, "band");
                if (!Double.isNaN(agc) && "GPS".equals(cons) && "L1".equals(band)) cur.agc = agc;
                if (!cons.isEmpty()) {
                    Sat sat = new Sat();
                    sat.cons = cons;
                    sat.band = band;
                    try { sat.svid = Integer.parseInt(get(c, idx, "svid")); } catch (NumberFormatException e) { sat.svid = 0; }
                    sat.cn0 = num(get(c, idx, "cn0_dbhz"));
                    sat.elev = num(get(c, idx, "elev_deg"));
                    sat.azim = num(get(c, idx, "azim_deg"));
                    sat.agc = agc;
                    sat.used = "1".equals(get(c, idx, "used_in_fix"));
                    cur.satList.add(sat);
                } else if (!Double.isNaN(agc) && Double.isNaN(cur.agc)) {
                    cur.agc = agc; // a second with no satellites still carries the AGC
                }
            }
            if (cur != null) finish(s, cur, keepRecent);
        } finally {
            in.close();
        }
        return s;
    }

    private static void finish(Summary s, Epoch e, int keepRecent) {
        if (Double.isNaN(e.agc)) {
            // Fall back to any AGC this second if GPS L1 wasn't reported.
            for (Sat sat : e.satList) if (!Double.isNaN(sat.agc)) { e.agc = sat.agc; break; }
        }
        if (s.seconds == 0) s.firstT = e.t;
        s.lastT = e.t;
        s.seconds++;
        String st = e.status.isEmpty() ? "?" : e.status;
        Integer n = s.statusCounts.get(st);
        s.statusCounts.put(st, n == null ? 1 : n + 1);
        if (!Double.isNaN(e.agc)) s.hasAgc = true;
        if (!Double.isNaN(e.top4)) {
            if (Double.isNaN(s.minTop4) || e.top4 < s.minTop4) s.minTop4 = e.top4;
            if (Double.isNaN(s.maxTop4) || e.top4 > s.maxTop4) s.maxTop4 = e.top4;
        }
        s.recent.addLast(e);
        if (s.recent.size() > keepRecent) s.recent.removeFirst();
    }

    private static String get(String[] c, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        return (i == null || i >= c.length) ? "" : c[i].trim();
    }

    private static double num(String v) {
        if (v == null || v.isEmpty()) return Double.NaN;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return Double.NaN; }
    }
}
