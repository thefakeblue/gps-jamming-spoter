package com.gpsjam.logger;

import android.annotation.SuppressLint;
import android.location.GnssStatus;

/**
 * The old satellite API (GpsStatus, used below Android 7) gives each satellite one number,
 * the "PRN", without saying which system it belongs to. Phones pack every system into one
 * range of numbers. This undoes the usual packing, the same one Android itself uses when it
 * turns new-style readings back into old ones (GLONASS +64, BeiDou +200, Galileo +300, SBAS -87).
 * Some older chips use their own numbering, so on those phones the system is a best guess.
 *
 * The GnssStatus constants are copied into the app when it is compiled, so they are safe
 * to use on Android 6 even though the GnssStatus class itself only exists from Android 7.
 */
@SuppressLint("InlinedApi")
public class OldGpsPrn {

    /** Which system (GnssStatus.CONSTELLATION_*) a PRN belongs to. */
    public static int constellation(int prn) {
        if (prn >= 1 && prn <= 32) return GnssStatus.CONSTELLATION_GPS;
        if (prn >= 33 && prn <= 64) return GnssStatus.CONSTELLATION_SBAS;
        if (prn >= 65 && prn <= 96) return GnssStatus.CONSTELLATION_GLONASS;
        if (prn >= 193 && prn <= 200) return GnssStatus.CONSTELLATION_QZSS;
        if (prn >= 201 && prn <= 263) return GnssStatus.CONSTELLATION_BEIDOU;
        if (prn >= 301 && prn <= 336) return GnssStatus.CONSTELLATION_GALILEO;
        return GnssStatus.CONSTELLATION_UNKNOWN;
    }

    /** The satellite's own number within its system (what GnssStatus.getSvid() would say). */
    public static int svid(int prn) {
        switch (constellation(prn)) {
            case GnssStatus.CONSTELLATION_SBAS: return prn + 87;     // 33-64 -> PRN 120-151
            case GnssStatus.CONSTELLATION_GLONASS: return prn - 64;  // 65-96 -> slot 1-32
            case GnssStatus.CONSTELLATION_BEIDOU: return prn - 200;
            case GnssStatus.CONSTELLATION_GALILEO: return prn - 300;
            default: return prn;
        }
    }
}
