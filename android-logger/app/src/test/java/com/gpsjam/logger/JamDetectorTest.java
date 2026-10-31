package com.gpsjam.logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Made-up skies with a known answer. Each test first feeds a minute of healthy signals so the
 * detector learns what "normal" is (top-4 C/N0 about 45 dB-Hz, AGC about 2 dB), then changes one thing.
 */
public class JamDetectorTest {

    private static final double NO_AGC = Double.NaN;

    /** 4 satellites high in the sky and 4 low ones, like a normal open-sky view. */
    private static List<JamDetector.Sat> sky(double high, double low) {
        List<JamDetector.Sat> s = new ArrayList<>();
        for (int i = 0; i < 4; i++) s.add(new JamDetector.Sat(high, 60));
        for (int i = 0; i < 4; i++) s.add(new JamDetector.Sat(low, 20));
        return s;
    }

    private static JamDetector learned(double agc) {
        JamDetector d = new JamDetector();
        for (int i = 0; i < 60; i++) assertEquals(JamDetector.NORMAL, d.evaluate(sky(45, 40), agc).status);
        return d;
    }

    @Test public void healthySkyIsNormalAndBaselineIsLearned() {
        JamDetector.Result r = learned(2.0).evaluate(sky(45, 40), 2.0);
        assertEquals(JamDetector.NORMAL, r.status);
        assertEquals(45.0, r.baseline, 0.01);
        assertEquals(2.0, r.agcBaseline, 0.01);
    }

    @Test public void signalDropWithAgcDropIsJamming() {
        // Every satellite 15 dB weaker and the AGC fell 5 dB: extra radio power arrived.
        JamDetector.Result r = learned(2.0).evaluate(sky(30, 25), -3.0);
        assertEquals(JamDetector.JAMMING, r.status);
    }

    @Test public void signalDropWithSmallAgcDropIsInterference() {
        // AGC fell 2.5 dB: more than the 2 dB warning level, less than the 3 dB jamming level.
        assertEquals(JamDetector.INTERFERENCE, learned(2.0).evaluate(sky(30, 25), -0.5).status);
    }

    @Test public void signalDropWithoutAgcDropIsBlockage() {
        // Same 15 dB drop, but the AGC didn't move: something is in the way (roof, trees).
        JamDetector.Result r = learned(2.0).evaluate(sky(30, 25), 2.0);
        assertEquals(JamDetector.BLOCKAGE, r.status);
        assertEquals(15.0, 45.0 - r.top4, 0.01);
    }

    @Test public void noSatellitesWithAgcDropIsJamming() {
        assertEquals(JamDetector.JAMMING, learned(2.0).evaluate(Collections.<JamDetector.Sat>emptyList(), -2.0).status);
    }

    @Test public void noSatellitesWithSteadyAgcIsNoSignal() {
        assertEquals(JamDetector.NO_SIGNAL, learned(2.0).evaluate(Collections.<JamDetector.Sat>emptyList(), 2.0).status);
    }

    // ---- basic mode: no AGC at all (Android 6, or phones that don't report it)

    @Test public void basicModeHealthySkyIsNormal() {
        JamDetector.Result r = learned(NO_AGC).evaluate(sky(45, 40), NO_AGC);
        assertEquals(JamDetector.NORMAL, r.status);
        assertEquals(Double.NaN, r.agcBaseline, 0);
    }

    @Test public void basicModeEverySatelliteWeakIsInterferenceNeverJamming() {
        // Even the high satellites lost 15 dB. Without AGC that's as far as the app will go.
        JamDetector.Result r = learned(NO_AGC).evaluate(sky(30, 25), NO_AGC);
        assertEquals(JamDetector.INTERFERENCE, r.status);
    }

    @Test public void basicModeModerateDropIsBlockage() {
        // 8 dB drop: degraded, but not the 10 dB "severe" level.
        assertEquals(JamDetector.BLOCKAGE, learned(NO_AGC).evaluate(sky(37, 30), NO_AGC).status);
    }

    @Test public void basicModeBigDropWithOneHighSatelliteIsBlockage() {
        // Only one satellite high up: too few to say "even the high ones are weak", so blockage.
        List<JamDetector.Sat> s = new ArrayList<>();
        s.add(new JamDetector.Sat(28, 60));
        for (int i = 0; i < 5; i++) s.add(new JamDetector.Sat(25, 20));
        assertEquals(JamDetector.BLOCKAGE, learned(NO_AGC).evaluate(s, NO_AGC).status);
    }

    @Test public void basicModeNoSatellitesIsNoSignalNotJamming() {
        assertEquals(JamDetector.NO_SIGNAL, learned(NO_AGC).evaluate(Collections.<JamDetector.Sat>emptyList(), NO_AGC).status);
    }

    @Test public void basicModeCanNeverSayJamming() {
        JamDetector d = learned(NO_AGC);
        for (double cn0 = 44; cn0 >= 10; cn0 -= 1) {
            assertNotEquals("C/N0 " + cn0, JamDetector.JAMMING, d.evaluate(sky(cn0, cn0 - 5), NO_AGC).status);
        }
    }

    // ---- learning

    @Test public void beforeLearningTheDefaultBaselineIsUsed() {
        JamDetector.Result r = new JamDetector().evaluate(sky(45, 40), 2.0);
        assertEquals(JamDetector.DEFAULT_BASELINE_CN0, r.baseline, 0);
        assertEquals(Double.NaN, r.agcBaseline, 0);
    }

    @Test public void longJamDoesNotDragTheBaselineDown() {
        JamDetector d = learned(2.0);
        for (int i = 0; i < 300; i++) d.evaluate(sky(30, 25), -3.0);
        JamDetector.Result r = d.evaluate(sky(30, 25), -3.0);
        assertEquals(JamDetector.JAMMING, r.status);
        assertEquals(45.0, r.baseline, 0.01);
        assertEquals(2.0, r.agcBaseline, 0.01);
    }

    @Test public void strongIdenticalSignalsAreUnusual() {
        List<JamDetector.Sat> s = new ArrayList<>();
        for (int i = 0; i < 8; i++) s.add(new JamDetector.Sat(50 + (i % 2) * 0.4, 30 + i * 5));
        assertEquals(JamDetector.UNUSUAL, new JamDetector().evaluate(s, 2.0).status);
    }
}
