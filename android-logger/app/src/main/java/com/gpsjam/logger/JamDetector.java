package com.gpsjam.logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns one second of satellite readings into a simple verdict:
 * NORMAL, BLOCKAGE, INTERFERENCE, JAMMING, NO_SIGNAL or UNUSUAL (spoofing-like).
 *
 * The idea:
 *  - A jammer adds noise, so C/N0 drops on ALL satellites at once, and the
 *    receiver's AGC (automatic gain control) turns its gain DOWN because the
 *    band suddenly has more power in it.
 *  - Trees/buildings/indoors also lower C/N0, but the AGC stays about the same,
 *    and usually some satellites (often the ones high in the sky) stay strong.
 *
 * The same rules are used by the map page (index.html, classify()) so the two agree.
 * RulesInSyncTest checks that the numbers below match the ones in index.html.
 */
public class JamDetector {

    public static final String NORMAL = "NORMAL";
    public static final String BLOCKAGE = "BLOCKAGE";
    public static final String INTERFERENCE = "INTERFERENCE";
    public static final String JAMMING = "JAMMING";
    public static final String NO_SIGNAL = "NO_SIGNAL";
    public static final String UNUSUAL = "UNUSUAL";

    // Tunable thresholds (dB). Same defaults as the map page.
    static final double DEFAULT_BASELINE_CN0 = 42.0; // used until we have learned a baseline
    static final double MIN_BASELINE_CN0 = 35.0;
    static final double DROP_WARN_DB = 6.0;        // C/N0 drop that counts as "degraded"
    static final double DROP_JAM_DB = 10.0;        // C/N0 drop that counts as "badly degraded"
    static final double AGC_DROP_WARN_DB = 2.0;    // AGC drop that suggests extra RF power
    static final double AGC_DROP_JAM_DB = 3.0;
    static final double HIGH_ELEV_DEG = 40;        // "high in the sky", for phones without AGC
    static final int UNUSUAL_MIN_SATS = 6;         // spoofing hint: this many satellites...
    static final double UNUSUAL_MIN_TOP4 = 48;     // ...all this strong...
    static final double UNUSUAL_MAX_STD = 1.5;     // ...and this alike
    static final int HISTORY = 600;                // ~10 minutes of 1 Hz epochs

    /** One satellite in one epoch. */
    public static class Sat {
        public double cn0;
        public double elev;
        public Sat(double cn0, double elev) { this.cn0 = cn0; this.elev = elev; }
    }

    /** Result for one epoch. */
    public static class Result {
        public String status = NORMAL;
        public int sats;
        public double top4 = Double.NaN;      // mean C/N0 of the 4 strongest satellites
        public double baseline = Double.NaN;  // what top4 normally is in this session
        public double agc = Double.NaN;       // mean L1-band AGC this epoch (NaN if phone doesn't report it)
        public double agcBaseline = Double.NaN;
        public String reason = "";
    }

    private final List<Double> top4History = new ArrayList<>();
    private final List<Double> agcHistory = new ArrayList<>();

    public Result evaluate(List<Sat> sats, double agc) {
        Result r = new Result();
        List<Double> cn0s = new ArrayList<>();
        for (Sat s : sats) if (s.cn0 > 0) cn0s.add(s.cn0);
        Collections.sort(cn0s, Collections.reverseOrder());
        r.sats = cn0s.size();
        r.agc = agc;

        if (!cn0s.isEmpty()) {
            int n = Math.min(4, cn0s.size());
            double sum = 0;
            for (int i = 0; i < n; i++) sum += cn0s.get(i);
            r.top4 = sum / n;
        }

        r.baseline = top4History.size() >= 20
                ? Math.max(MIN_BASELINE_CN0, percentile(top4History, 0.75))
                : DEFAULT_BASELINE_CN0;
        boolean haveAgc = !Double.isNaN(agc);
        if (haveAgc && agcHistory.size() >= 20) r.agcBaseline = percentile(agcHistory, 0.75);

        double drop = Double.isNaN(r.top4) ? 99 : r.baseline - r.top4;
        double agcDrop = (haveAgc && !Double.isNaN(r.agcBaseline)) ? r.agcBaseline - agc : Double.NaN;
        boolean agcKnown = !Double.isNaN(agcDrop);

        if (r.sats == 0) {
            if (agcKnown && agcDrop >= AGC_DROP_WARN_DB) {
                r.status = JAMMING;
                r.reason = "No satellites and AGC fell " + fmt(agcDrop) + " dB";
            } else {
                r.status = NO_SIGNAL;
                r.reason = agcKnown ? "No satellites, AGC normal (indoors?)" : "No satellites heard";
            }
        } else if (drop >= DROP_WARN_DB) {
            if (agcKnown) {
                if (drop >= DROP_JAM_DB && agcDrop >= AGC_DROP_JAM_DB) {
                    r.status = JAMMING;
                } else if (agcDrop >= AGC_DROP_WARN_DB) {
                    r.status = INTERFERENCE;
                } else {
                    r.status = BLOCKAGE;
                }
                r.reason = "C/N0 down " + fmt(drop) + " dB, AGC down " + fmt(agcDrop) + " dB";
            } else {
                // No AGC on this phone: look at the satellites high in the sky.
                // Blockage rarely hides them all; a jammer hurts them just as much.
                double highSum = 0; int highN = 0;
                for (Sat s : sats) if (s.cn0 > 0 && s.elev >= HIGH_ELEV_DEG) { highSum += s.cn0; highN++; }
                boolean highAlsoWeak = highN >= 2 && (r.baseline - highSum / highN) >= DROP_JAM_DB;
                if (highAlsoWeak && drop >= DROP_JAM_DB) {
                    r.status = INTERFERENCE;
                    r.reason = "All satellites weak, even high ones (no AGC to confirm)";
                } else {
                    r.status = BLOCKAGE;
                    r.reason = "C/N0 down " + fmt(drop) + " dB (no AGC on this phone)";
                }
            }
        } else if (r.sats >= UNUSUAL_MIN_SATS && r.top4 >= UNUSUAL_MIN_TOP4 && stdDev(cn0s) < UNUSUAL_MAX_STD) {
            r.status = UNUSUAL;
            r.reason = "Very strong and very uniform signals (spoofing check)";
        }

        // Learn the baseline only from epochs that look healthy-ish, so a long jam
        // doesn't drag the baseline down with it.
        if (!Double.isNaN(r.top4) && (NORMAL.equals(r.status) || BLOCKAGE.equals(r.status))) {
            push(top4History, r.top4);
        }
        if (haveAgc && !JAMMING.equals(r.status) && !INTERFERENCE.equals(r.status)) {
            push(agcHistory, agc);
        }
        return r;
    }

    private static void push(List<Double> list, double v) {
        list.add(v);
        if (list.size() > HISTORY) list.remove(0);
    }

    static double percentile(List<Double> values, double p) {
        List<Double> s = new ArrayList<>(values);
        Collections.sort(s);
        int idx = (int) Math.round(p * (s.size() - 1));
        return s.get(Math.max(0, Math.min(s.size() - 1, idx)));
    }

    static double stdDev(List<Double> v) {
        if (v.size() < 2) return 0;
        double m = 0;
        for (double x : v) m += x;
        m /= v.size();
        double ss = 0;
        for (double x : v) ss += (x - m) * (x - m);
        return Math.sqrt(ss / (v.size() - 1));
    }

    static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }
}
