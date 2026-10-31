package com.gpsjam.logger;

import static org.junit.Assert.assertEquals;

import android.location.GnssStatus;

import org.junit.Test;

/** The old API's satellite numbers, unpacked into system + satellite number. */
public class OldGpsPrnTest {

    private static void check(int prn, int cons, int svid) {
        assertEquals("constellation of PRN " + prn, cons, OldGpsPrn.constellation(prn));
        assertEquals("svid of PRN " + prn, svid, OldGpsPrn.svid(prn));
    }

    @Test public void unpacksEachSystem() {
        check(1, GnssStatus.CONSTELLATION_GPS, 1);
        check(32, GnssStatus.CONSTELLATION_GPS, 32);
        check(33, GnssStatus.CONSTELLATION_SBAS, 120);
        check(51, GnssStatus.CONSTELLATION_SBAS, 138);   // WAAS
        check(65, GnssStatus.CONSTELLATION_GLONASS, 1);
        check(88, GnssStatus.CONSTELLATION_GLONASS, 24);
        check(193, GnssStatus.CONSTELLATION_QZSS, 193);
        check(201, GnssStatus.CONSTELLATION_BEIDOU, 1);
        check(237, GnssStatus.CONSTELLATION_BEIDOU, 37);
        check(301, GnssStatus.CONSTELLATION_GALILEO, 1);
        check(336, GnssStatus.CONSTELLATION_GALILEO, 36);
    }

    @Test public void unknownNumbersStayAsTheyAre() {
        check(0, GnssStatus.CONSTELLATION_UNKNOWN, 0);
        check(150, GnssStatus.CONSTELLATION_UNKNOWN, 150);
        check(400, GnssStatus.CONSTELLATION_UNKNOWN, 400);
    }
}
